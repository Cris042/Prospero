import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Historico } from './historico';
import { Lancamento, Negocio } from './historico.service';

const NEGOCIOS = '/servicos/back-office/api/v1/negocios';
const RAZAO = '/servicos/back-office/api/v1/razao';
const negocio = (numero = 1, extra: Partial<Negocio> = {}): Negocio => ({
  negocio: `ALFA3-2026-10-23-${numero}`,
  ticker: 'ALFA3',
  lado: 'COMPRADOR',
  quantidade: '100',
  valor: '2000.00',
  dataSessao: '2026-10-23',
  dataLiquidacao: '2026-10-27',
  liquidacao: 'PENDENTE',
  ...extra,
});
const lancamento = (extra: Partial<Lancamento> = {}): Lancamento => ({
  transacao: 'd2-qa-transacao',
  negocio: 'ALFA3-2026-10-23-1',
  lado: 'COMPRADOR',
  momento: 'D2',
  data: '2026-10-27',
  contaContabil: 'CUSTODIA',
  natureza: 'DEBITO',
  unidade: 'ALFA3',
  valor: '100',
  ...extra,
});

describe('Histórico e liquidação por HTTP', () => {
  let fixture: ComponentFixture<Historico>;
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      imports: [Historico],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Historico);
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    http.verify({ ignoreCancelled: true });
    vi.useRealTimers();
  });

  function texto() {
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function pedido(caminho: string, cursor: string | null = null) {
    const p = http.expectOne((r) => r.url === caminho && r.params.get('cursor') === cursor);
    expect(p.request.method).toBe('GET');
    expect(p.request.params.get('limite')).toBe('20');
    expect(p.request.headers.has('Authorization')).toBe(false);
    return p;
  }

  function botao(nome: string): HTMLButtonElement {
    fixture.detectChanges();
    const botoes = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'));
    const encontrado = botoes.find((b) => b.textContent?.trim() === nome);
    expect(encontrado, `botão ${nome}`).toBeDefined();
    return encontrado!;
  }

  function primeiraPagina(negocios: Negocio[] = [negocio()], cursor: string | null = null) {
    pedido(NEGOCIOS).flush({ conta: 'CONTA-001', negocios, proximoCursor: cursor });
    pedido(RAZAO).flush({ conta: 'CONTA-001', lancamentos: [lancamento()], proximoCursor: null });
    fixture.detectChanges();
  }

  it('apresenta os lados, datas, liquidação e valores textuais exatos da própria conta', () => {
    primeiraPagina([negocio(1, { quantidade: '9007199254740993', valor: '9007199254740993.01' })]);
    const pagina = texto();
    expect(pagina).toContain('Histórico e liquidação');
    expect(pagina).toContain('Razão da conta');
    expect(pagina).toContain('27/10/2026');
    expect(pagina).toContain('9.007.199.254.740.993');
    expect(pagina).toContain('9.007.199.254.740.993,01');
    expect(pagina).toContain('A liquidar');
    expect(pagina).toContain('CONTA-001');
    expect(pagina).not.toContain('CONTA-002');
  });

  it('503 inicial não fabrica histórico vazio confirmado e permite recuperar', () => {
    pedido(NEGOCIOS).flush({}, { status: 503, statusText: 'Service Unavailable' });
    pedido(RAZAO).flush({}, { status: 503, statusText: 'Service Unavailable' });
    expect(texto()).toContain('Não foi possível');
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr')).toHaveLength(0);
    botao('Atualizar histórico').click();
    primeiraPagina();
    expect(texto()).toContain('ALFA3-2026-10-23-1');
  });

  it('falha ao carregar próxima página preserva snapshot e cursor para nova tentativa', () => {
    primeiraPagina([negocio()], 'cursor-proxima');
    botao('Mais negócios').click();
    pedido(NEGOCIOS, 'cursor-proxima').flush(
      {},
      { status: 503, statusText: 'Service Unavailable' },
    );
    expect(texto()).toContain('ALFA3-2026-10-23-1');
    expect(texto()).toContain('última');
    botao('Mais negócios').click();
    pedido(NEGOCIOS, 'cursor-proxima').flush({
      conta: 'CONTA-001',
      negocios: [negocio(2)],
      proximoCursor: null,
    });
    expect(texto()).toContain('ALFA3-2026-10-23-1');
    expect(texto()).toContain('ALFA3-2026-10-23-2');
    http.expectNone((r) => r.url === RAZAO);
  });

  it('atualização lenta ou com erro conserva snapshot e não sobrepõe consultas', () => {
    primeiraPagina();
    botao('Atualizar histórico').click();
    const negocios = pedido(NEGOCIOS);
    const razao = pedido(RAZAO);
    expect(texto()).toContain('ALFA3-2026-10-23-1');
    botao('Atualizar histórico').click();
    http.expectNone((r) => r.url === NEGOCIOS || r.url === RAZAO);
    negocios.flush({}, { status: 503, statusText: 'Service Unavailable' });
    razao.flush({}, { status: 503, statusText: 'Service Unavailable' });
    expect(texto()).toContain('ALFA3-2026-10-23-1');
    expect(texto()).toContain('última');
  });

  it('paginação para em cem itens e atualização reinicia na primeira página', () => {
    const pagina = (inicio: number) => Array.from({ length: 20 }, (_, i) => negocio(inicio + i));
    primeiraPagina(pagina(1), 'c2');
    for (let i = 2; i <= 5; i++) {
      botao('Mais negócios').click();
      pedido(NEGOCIOS, `c${i}`).flush({
        conta: 'CONTA-001',
        negocios: pagina((i - 1) * 20 + 1),
        proximoCursor: `c${i + 1}`,
      });
    }
    expect(botao('Mais negócios').disabled).toBe(true);
    botao('Mais negócios').click();
    http.expectNone((r) => r.url === NEGOCIOS);
    botao('Atualizar histórico').click();
    primeiraPagina([negocio(101)]);
    expect(texto()).not.toContain('ALFA3-2026-10-23-100');
    expect(texto()).toContain('ALFA3-2026-10-23-101');
  });

  it('destruir componente cancela chamadas pendentes e não inicia novos ciclos', () => {
    const negocios = pedido(NEGOCIOS);
    const razao = pedido(RAZAO);
    fixture.destroy();
    expect(negocios.cancelled).toBe(true);
    expect(razao.cancelled).toBe(true);
    vi.advanceTimersByTime(30000);
    http.expectNone((r) => r.url === NEGOCIOS || r.url === RAZAO);
  });

  it('razão pagina independentemente e preserva partidas depois de erro na próxima página', () => {
    pedido(NEGOCIOS).flush({ conta: 'CONTA-001', negocios: [], proximoCursor: null });
    pedido(RAZAO).flush({
      conta: 'CONTA-001',
      lancamentos: [lancamento()],
      proximoCursor: 'razao-c2',
    });
    botao('Mais lançamentos').click();
    pedido(RAZAO, 'razao-c2').flush({}, { status: 503, statusText: 'Service Unavailable' });
    expect(texto()).toContain('CUSTODIA');
    expect(texto()).toContain('última consulta confirmada');
    botao('Mais lançamentos').click();
    pedido(RAZAO, 'razao-c2').flush({
      conta: 'CONTA-001',
      lancamentos: [lancamento({ contaContabil: 'CUSTODIA_A_RECEBER', natureza: 'CREDITO' })],
      proximoCursor: null,
    });
    expect(texto()).toContain('CUSTODIA_A_RECEBER');
    expect(botao('Mais lançamentos').disabled).toBe(true);
    http.expectNone((r) => r.url === NEGOCIOS);
  });

  it('timeout cancela consultas e devolve o controle de atualização sem produzir dados', () => {
    const negocios = pedido(NEGOCIOS);
    const razao = pedido(RAZAO);
    vi.advanceTimersByTime(8000);
    expect(negocios.cancelled).toBe(true);
    expect(razao.cancelled).toBe(true);
    expect(texto()).toContain('Não foi possível');
    expect(botao('Atualizar histórico').disabled).toBe(false);
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr')).toHaveLength(0);
  });
});
