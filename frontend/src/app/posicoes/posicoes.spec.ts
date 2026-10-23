import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Posicoes } from './posicoes';
import { PosicaoResumo, PosicoesResumo } from './posicoes.service';

const BASE = '/servicos/back-office/api/v1/posicoes';
const resumo = (extra: Partial<PosicaoResumo> = {}): PosicoesResumo => ({
  conta: 'CONTA-002',
  base: 'NEGOCIOS_DESDE_ORIGEM',
  sincronizado: true,
  posicoes: [
    {
      ticker: 'ALFA3',
      quantidade: '6',
      lado: 'COMPRADA',
      precoMedio: '100.0000',
      resultadoRealizado: '40.0000',
      resultadoNaoRealizado: '30.0000',
      cotacao: '105.00',
      sessaoCotacao: '2026-10-23',
      cotacaoDefasada: false,
      revisaoCotacao: 3,
      atualizadaEm: '2026-10-23T12:00:00Z',
      ...extra,
    },
  ],
});

describe('Posições e resultado por HTTP', () => {
  let fixture: ComponentFixture<Posicoes>;
  let http: HttpTestingController;
  let oculto: boolean;

  beforeEach(() => {
    vi.useFakeTimers();
    oculto = false;
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() =>
      oculto ? 'hidden' : 'visible',
    );
    TestBed.configureTestingModule({
      imports: [Posicoes],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Posicoes);
    fixture.detectChanges();
    vi.advanceTimersByTime(0);
  });

  afterEach(() => {
    fixture.destroy();
    http.verify({ ignoreCancelled: true });
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function texto() {
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('preserva quantidade e resultado textuais além da precisão de Number e exibe quatro casas', () => {
    http
      .expectOne(BASE)
      .flush(
        resumo({ quantidade: '9007199254740993', resultadoRealizado: '9007199254740993.0001' }),
      );
    const pagina = texto();
    expect(pagina).toContain('9.007.199.254.740.993');
    expect(pagina).toContain('9.007.199.254.740.993,0001');
    expect(pagina).toContain('100,0000');
    expect(pagina).toContain('30,0000');
    expect(pagina).toContain('CONTA-002');
    expect(pagina).toContain('A custódia inicial e a liquidação não entram');
  });

  it('distingue posição vendida, ausência de preço médio e cotação defasada', () => {
    http.expectOne(BASE).flush(
      resumo({
        quantidade: '-3',
        lado: 'VENDIDA',
        precoMedio: null,
        resultadoNaoRealizado: null,
        cotacao: null,
        cotacaoDefasada: true,
      }),
    );
    const pagina = texto();
    expect(pagina).toContain('−3');
    expect(pagina).toContain('Vendida');
    expect(pagina).toContain('Sem cotação');
    expect(fixture.nativeElement.querySelectorAll('td')[1].textContent).toContain('—');
    expect(fixture.nativeElement.querySelectorAll('td')[3].textContent).toContain('—');
  });

  it('503 inicial informa indisponibilidade sem inventar posições zeradas e recupera automaticamente', () => {
    http.expectOne(BASE).flush({ status: 503 }, { status: 503, statusText: 'Service Unavailable' });
    expect(texto()).toContain('Não foi possível atualizar as posições');
    expect(fixture.nativeElement.querySelector('table')).toBeNull();
    vi.advanceTimersByTime(2000);
    http.expectOne(BASE).flush(resumo());
    expect(texto()).toContain('40,0000');
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
  });

  it('cotação defasada conserva a referência e identifica sua sessão', () => {
    http.expectOne(BASE).flush(resumo({ cotacaoDefasada: true, sessaoCotacao: '2026-10-22' }));
    const pagina = texto();
    expect(pagina).toContain('Referência defasada');
    expect(pagina).toContain('22/10/2026');
    expect(pagina).toContain('105,00');
    expect(pagina).toContain('40,0000');
  });

  it('preserva o último snapshot confirmado durante nova consulta e falha', () => {
    http.expectOne(BASE).flush(resumo());
    vi.advanceTimersByTime(2000);
    const proxima = http.expectOne(BASE);
    expect(texto()).toContain('40,0000');
    proxima.flush({}, { status: 503, statusText: 'Service Unavailable' });
    expect(texto()).toContain('última consulta confirmada');
    expect(texto()).toContain('40,0000');
    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(1);
  });

  it('não sobrepõe requisições lentas nem pelo botão de atualização', () => {
    const primeira = http.expectOne(BASE);
    vi.advanceTimersByTime(6000);
    fixture.componentInstance.atualizar();
    http.expectNone(BASE);
    expect(primeira.cancelled).toBe(false);
    primeira.flush(resumo());
    vi.advanceTimersByTime(2000);
    http.expectOne(BASE).flush(resumo({ revisaoCotacao: 4 }));
    expect(texto()).toContain('revisão 4');
  });

  it('timeout cancela a chamada e exibe falha sem fabricar resultado', () => {
    const primeira = http.expectOne(BASE);
    vi.advanceTimersByTime(8000);
    expect(primeira.cancelled).toBe(true);
    expect(texto()).toContain('Não foi possível atualizar as posições');
    expect(fixture.nativeElement.querySelector('table')).toBeNull();
  });

  it('ocultar cancela consulta, voltar consulta imediatamente e destruir encerra os ciclos', () => {
    const primeira = http.expectOne(BASE);
    oculto = true;
    document.dispatchEvent(new Event('visibilitychange'));
    expect(primeira.cancelled).toBe(true);
    vi.advanceTimersByTime(6000);
    http.expectNone(BASE);
    oculto = false;
    document.dispatchEvent(new Event('visibilitychange'));
    vi.advanceTimersByTime(0);
    const retomada = http.expectOne(BASE);
    fixture.destroy();
    expect(retomada.cancelled).toBe(true);
    vi.advanceTimersByTime(6000);
    http.expectNone(BASE);
  });
});
