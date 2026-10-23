import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Mercado } from './mercado';

const BASE = '/servicos/front-office/api/v1/ativos';
const resumo = (ticker = 'ALFA3', extra: Record<string, unknown> = {}) => ({
  ticker,
  nome: ticker,
  precoReferencia: '10.00',
  ultimoPreco: null,
  variacao: null,
  variacaoPercentual: null,
  melhorCompra: null,
  melhorVenda: null,
  spread: null,
  volumeQuantidade: null,
  volumeFinanceiro: null,
  vwap: null,
  estadoLivro: 'VAZIO',
  preparadoEm: null,
  defasado: false,
  origem: 'MATCHING',
  sessao: '2026-10-22',
  revisao: 1,
  ...extra,
});
const snapshot = (ticker = 'ALFA3', extra: Record<string, unknown> = {}) => ({
  ticker,
  estadoLivro: 'VAZIO',
  compra: { niveis: [], niveisTotal: 0, quantidadeTotal: 0 },
  venda: { niveis: [], niveisTotal: 0, quantidadeTotal: 0 },
  revisao: 1,
  sessao: '2026-10-22',
  preparadoEm: null,
  defasado: false,
  origem: 'MATCHING',
  ...extra,
});

describe('Atualização automática do mercado por HTTP', () => {
  let fixture: ComponentFixture<Mercado>;
  let http: HttpTestingController;
  let oculto: boolean;

  beforeEach(() => {
    vi.useFakeTimers();
    oculto = false;
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() =>
      oculto ? 'hidden' : 'visible',
    );
    TestBed.configureTestingModule({
      imports: [Mercado],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Mercado);
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    http.verify({ ignoreCancelled: true });
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function carregar() {
    http.expectOne(BASE).flush([resumo(), resumo('BETA3')]);
    http.expectOne(`${BASE}/ALFA3/livro`).flush(snapshot());
    fixture.detectChanges();
  }

  it('mostra VWAP da sessão com quatro casas e preserva precisão textual', () => {
    http
      .expectOne(BASE)
      .flush([
        resumo('ALFA3', { vwap: '9007199254740993.0001' }),
        resumo('BETA3', { vwap: '10.3500' }),
        resumo('GAMA3'),
      ]);
    http.expectOne(`${BASE}/ALFA3/livro`).flush(snapshot());
    fixture.detectChanges();
    const tabela = fixture.nativeElement.querySelector('table') as HTMLTableElement;
    expect(tabela.textContent).toContain('VWAP');
    expect(tabela.textContent).toContain('9.007.199.254.740.993,0001');
    expect(tabela.textContent).toContain('10,3500');
    expect(tabela.querySelectorAll('tbody tr')[2].textContent).toContain('—');
  });

  it('consulta novamente um segundo após concluir e não sobrepõe chamadas lentas', () => {
    carregar();
    vi.advanceTimersByTime(999);
    http.expectNone(BASE);
    vi.advanceTimersByTime(1);
    const lista = http.expectOne(BASE);
    const livro = http.expectOne(`${BASE}/ALFA3/livro`);
    vi.advanceTimersByTime(3000);
    expect(lista.cancelled).toBe(false);
    expect(livro.cancelled).toBe(false);
    http.expectNone(BASE);
    http.expectNone(`${BASE}/ALFA3/livro`);
    lista.flush([resumo()]);
    livro.flush(snapshot());
    vi.advanceTimersByTime(999);
    http.expectNone(BASE);
    vi.advanceTimersByTime(1);
    http.expectOne(BASE).flush([resumo()]);
    http.expectOne(`${BASE}/ALFA3/livro`).flush(snapshot());
  });
  it('preserva snapshot em falha e respeita recuo 1/2/4/8/8 sem atualização manual', () => {
    carregar();
    // O livro continua consultável mesmo quando a listagem falha.
    for (const atraso of [1000, 1000, 2000, 4000, 8000, 8000]) {
      vi.advanceTimersByTime(atraso - 1);
      http.expectNone(BASE);
      vi.advanceTimersByTime(1);
      http.expectOne(BASE).flush({}, { status: 429, statusText: 'Too Many Requests' });
      for (const pedido of http.match(`${BASE}/ALFA3/livro`)) {
        if (!pedido.cancelled) pedido.flush(snapshot());
      }
      expect(fixture.componentInstance.ativos()[0].ticker).toBe('ALFA3');
      expect(fixture.componentInstance.erro()).toBe(true);
      fixture.componentInstance.atualizar();
      http.expectNone(BASE);
    }
    vi.advanceTimersByTime(8000);
    http.expectOne(BASE).flush([resumo()]);
    for (const pedido of http.match(`${BASE}/ALFA3/livro`)) {
      if (!pedido.cancelled) pedido.flush(snapshot());
    }
    expect(fixture.componentInstance.erro()).toBe(false);
    vi.advanceTimersByTime(1000);
    http.expectOne(BASE).flush([resumo()]);
    for (const pedido of http.match(`${BASE}/ALFA3/livro`)) {
      if (!pedido.cancelled) pedido.flush(snapshot());
    }
  });

  it('mantém livro e metadados próprios quando sua consulta falha e a lista avança', () => {
    carregar();
    vi.advanceTimersByTime(1000);
    http.expectOne(BASE).flush([resumo('ALFA3', { revisao: 90, defasado: true })]);
    http.expectOne(`${BASE}/ALFA3/livro`).flush({}, { status: 503, statusText: 'Unavailable' });
    expect(fixture.componentInstance.livro()?.revisao).toBe(1);
    expect(fixture.componentInstance.livro()?.defasado).toBe(false);
    expect(fixture.componentInstance.erroLivro()).toBe(true);
    vi.advanceTimersByTime(1000);
    http.expectOne(BASE).flush([resumo('ALFA3', { revisao: 91 })]);
    http.expectOne(`${BASE}/ALFA3/livro`).flush(snapshot('ALFA3', { revisao: 2 }));
    expect(fixture.componentInstance.erroLivro()).toBe(false);
    expect(fixture.componentInstance.livro()?.revisao).toBe(2);
  });

  it('não recarrega nem apaga o livro ao selecionar o mesmo ticker', () => {
    carregar();
    fixture.componentInstance.selecionar('ALFA3');
    http.expectNone(`${BASE}/ALFA3/livro`);
    expect(fixture.componentInstance.livro()?.ticker).toBe('ALFA3');
    fixture.componentInstance.atualizar();
    http.expectOne(BASE).flush([resumo()]);
    for (const pedido of http.match(`${BASE}/ALFA3/livro`)) {
      if (!pedido.cancelled) pedido.flush(snapshot());
    }
    expect(fixture.componentInstance.livro()?.ticker).toBe('ALFA3');
  });

  it('cancela livro antigo ao trocar ticker sem atribuir resposta antiga ao novo', () => {
    carregar();
    vi.advanceTimersByTime(1000);
    http.expectOne(BASE).flush([resumo(), resumo('BETA3')]);
    const antigo = http.expectOne(`${BASE}/ALFA3/livro`);
    fixture.componentInstance.selecionar('BETA3');
    expect(antigo.cancelled).toBe(true);
    expect(fixture.componentInstance.livro()).toBeNull();
    const novo = http.expectOne(`${BASE}/BETA3/livro`);
    expect(fixture.componentInstance.carregandoLivro()).toBe(true);
    novo.flush(snapshot('BETA3'));
    expect(fixture.componentInstance.livro()?.ticker).toBe('BETA3');
  });

  it('cancela consultas ao ocultar, pausa timers e retoma uma vez imediatamente', () => {
    carregar();
    vi.advanceTimersByTime(1000);
    const lista = http.expectOne(BASE);
    const livro = http.expectOne(`${BASE}/ALFA3/livro`);
    oculto = true;
    document.dispatchEvent(new Event('visibilitychange'));
    expect(lista.cancelled).toBe(true);
    expect(livro.cancelled).toBe(true);
    vi.advanceTimersByTime(60000);
    http.expectNone(() => true);
    oculto = false;
    document.dispatchEvent(new Event('visibilitychange'));
    http.expectOne(BASE).flush([resumo()]);
    http.expectOne(`${BASE}/ALFA3/livro`).flush(snapshot());
    document.dispatchEvent(new Event('visibilitychange'));
    http.expectNone(() => true);
  });

  it('destruir cancela consultas e impede novos GET inclusive na retomada visível', () => {
    carregar();
    vi.advanceTimersByTime(1000);
    const lista = http.expectOne(BASE);
    const livro = http.expectOne(`${BASE}/ALFA3/livro`);
    fixture.destroy();
    expect(lista.cancelled).toBe(true);
    expect(livro.cancelled).toBe(true);
    vi.advanceTimersByTime(60000);
    document.dispatchEvent(new Event('visibilitychange'));
    http.expectNone(() => true);
  });

  it('mantém timeout de oito segundos sem abortar a cada tick e recupera sozinho', () => {
    const lento = http.expectOne(BASE);
    vi.advanceTimersByTime(7999);
    expect(lento.cancelled).toBe(false);
    http.expectNone(BASE);
    vi.advanceTimersByTime(1);
    expect(lento.cancelled).toBe(true);
    expect(fixture.componentInstance.erro()).toBe(true);
    vi.advanceTimersByTime(999);
    http.expectNone(BASE);
    vi.advanceTimersByTime(1);
    http.expectOne(BASE).flush([resumo()]);
    http.expectOne(`${BASE}/ALFA3/livro`).flush(snapshot());
    expect(fixture.componentInstance.erro()).toBe(false);
  });
  it('mantém anúncios acessíveis estáveis durante consultas periódicas bem-sucedidas', () => {
    carregar();
    const anuncio = () => fixture.nativeElement.querySelector('[aria-live]')?.textContent;
    const antes = anuncio();
    expect(antes).toContain('ALFA3');
    vi.advanceTimersByTime(1000);
    const lista = http.expectOne(BASE);
    const livro = http.expectOne(`${BASE}/ALFA3/livro`);
    fixture.detectChanges();
    expect(anuncio()).toBe(antes);
    lista.flush([resumo()]);
    livro.flush(snapshot('ALFA3', { revisao: 2 }));
    fixture.detectChanges();
    expect(anuncio()).toBe(antes);
  });

  it('não consulta durante carregamento manual e mantém falha visível durante retentativa', () => {
    fixture.componentInstance.atualizar();
    http.expectOne(BASE).flush({}, { status: 503, statusText: 'Unavailable' });
    vi.advanceTimersByTime(1000);
    const retentativa = http.expectOne(BASE);
    expect(fixture.componentInstance.erro()).toBe(true);
    fixture.componentInstance.atualizar();
    http.expectNone(BASE);
    retentativa.flush([resumo()]);
    http.expectOne(`${BASE}/ALFA3/livro`).flush(snapshot());
    expect(fixture.componentInstance.erro()).toBe(false);
  });
});
