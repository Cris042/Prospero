import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Subject } from 'rxjs';
import { AtualizacoesService } from './atualizacoes.service';
import { Trajetoria } from './trajetoria';

const ORDEM = '01234567-1234-4234-8234-123456789abc';
const OUTRA = '11234567-1234-4234-8234-123456789abc';
const TRACE = '0123456789abcdef0123456789abcdef';
function marco(sequencia: number, origem = 'front-office', tipo = 'ORDEM_RECEBIDA') {
  return {
    sequencia: String(sequencia),
    origem,
    tipo,
    evento: '21234567-1234-4234-8234-123456789abc',
    causacao: null,
    negocio: origem === 'back-office' ? 'NEGOCIO-QA-42' : null,
    ocorridoEm: '2026-10-25T10:00:00Z',
    observadoEm: '2026-10-25T10:00:01Z',
    quantidade: '10',
    motivo: null,
    traceId: TRACE,
  };
}
function pagina(eventos = [marco(1)], completo = true, cursor: string | null = null) {
  return {
    ordem: ORDEM,
    conta: 'CONTA-002',
    revisao: '9007199254740993',
    historicoCompleto: completo,
    disponivelDesde: '2026-10-25T10:00:01Z',
    eventos,
    proximoCursor: cursor,
  };
}

describe('Linha do tempo por ordem', () => {
  let fixture: ComponentFixture<Trajetoria>;
  let http: HttpTestingController;
  let avisos: Subject<void>;
  beforeEach(() => {
    avisos = new Subject<void>();
    TestBed.configureTestingModule({
      imports: [Trajetoria],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AtualizacoesService,
          useValue: { avisos$: avisos.asObservable(), estado: signal('online') },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Trajetoria);
    fixture.componentRef.setInput('ordem', ORDEM);
    fixture.detectChanges();
  });
  afterEach(() => {
    fixture.destroy();
    http.verify({ ignoreCancelled: true });
  });
  function texto() {
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }
  function pedido(ordem = ORDEM) {
    return http.expectOne((p) => p.url.endsWith(`/ordens/${ordem}/trajetoria`));
  }
  function botao(nome: RegExp) {
    fixture.detectChanges();
    const botao = [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find((b) =>
      nome.test(b.textContent ?? ''),
    );
    expect(botao).toBeDefined();
    return botao as HTMLButtonElement;
  }

  it('mostra fatos dos quatro serviços, identidades e informação de histórico completo', () => {
    pedido().flush(
      pagina([
        marco(1),
        marco(2, 'middle-office', 'RISCO_RESERVADO'),
        marco(3, 'matching-engine', 'NEGOCIO'),
        marco(4, 'back-office', 'NEGOCIO_CONTABILIZADO'),
      ]),
    );
    const tela = texto();
    for (const origem of [
      /front.?office/i,
      /middle.?office/i,
      /matching.?engine/i,
      /back.?office/i,
    ])
      expect(tela).toMatch(origem);
    expect(tela).toContain(ORDEM);
    expect(tela).toContain('NEGOCIO-QA-42');
    expect(tela).toContain(TRACE);
    expect(tela).toMatch(/hist[oó]rico completo/i);
  });

  it('declara legado incompleto e preserva snapshot quando atualização falha', () => {
    pedido().flush(pagina([marco(1)], false));
    expect(texto()).toMatch(/incompleto|legado|parcial/i);
    avisos.next();
    fixture.detectChanges();
    pedido().flush({ codigo: 'INDISPONIVEL' }, { status: 503, statusText: 'Indisponível' });
    expect(texto()).toContain(TRACE);
    expect(texto()).toMatch(/erro|falha|indispon[ií]vel|não foi possível/i);
    botao(/atualizar/i).click();
    pedido().flush(pagina([marco(1)], false));
  });

  it('aviso durante GET gera uma leitura posterior e troca de ordem cancela pedido anterior', () => {
    const primeira = pedido();
    for (let i = 0; i < 30; i++) avisos.next();
    fixture.detectChanges();
    http.expectNone((p) => p.url.endsWith(`/ordens/${ORDEM}/trajetoria`));
    primeira.flush(pagina([marco(1)]));
    fixture.detectChanges();
    const posterior = pedido();
    expect(fixture.componentInstance.carregando()).toBe(true);
    fixture.componentRef.setInput('ordem', OUTRA);
    fixture.detectChanges();
    expect(posterior.cancelled).toBe(true);
    pedido(OUTRA).flush({ ...pagina([marco(2)]), ordem: OUTRA });
    expect(texto()).toContain(OUTRA);
  });

  it('carrega próxima página pelo cursor sem duplicar os eventos já exibidos', () => {
    pedido().flush(pagina([marco(1)], true, 'cursor-opaco'));
    botao(/carregar mais/i).click();
    const proxima = pedido();
    expect(proxima.request.params.get('cursor')).toBe('cursor-opaco');
    proxima.flush(pagina([marco(2, 'back-office', 'NEGOCIO_CONTABILIZADO')]));
    expect(texto()).toContain('NEGOCIO-QA-42');
    expect(texto()).toContain(TRACE);
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('ol > li').length).toBe(2);
  });

  it('limita a cem marcos e informa quando ainda há histórico disponível', () => {
    pedido().flush(
      pagina(
        Array.from({ length: 20 }, (_, i) => marco(i + 1)),
        true,
        'pagina-2',
      ),
    );
    for (let numero = 2; numero <= 5; numero++) {
      botao(/carregar mais/i).click();
      pedido().flush(
        pagina(
          Array.from({ length: 20 }, (_, i) => marco((numero - 1) * 20 + i + 1)),
          true,
          `pagina-${numero + 1}`,
        ),
      );
    }
    expect(texto()).toMatch(/100 eventos|cem eventos/i);
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('ol > li').length).toBe(100);
    const carregar = [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find(
      (b) => /carregar mais/i.test(b.textContent ?? ''),
    );
    expect(!carregar || carregar.disabled).toBe(true);
  });
});
