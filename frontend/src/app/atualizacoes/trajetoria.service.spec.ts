import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TrajetoriaService } from './trajetoria.service';

const ORDEM = '01234567-1234-4234-8234-123456789abc';
describe('Consulta HTTP da trajetória', () => {
  let http: HttpTestingController;
  let servico: TrajetoriaService;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    servico = TestBed.inject(TrajetoriaService);
  });
  afterEach(() => {
    http.verify({ ignoreCancelled: true });
    vi.useRealTimers();
  });

  it('usa GET limitado e cursor opaco como parâmetro sem credencial no navegador', () => {
    const cursor = 'opaco+/?&conta=CONTA-999';
    servico.consultar(ORDEM, cursor).subscribe();
    const pedido = http.expectOne((p) => p.url.endsWith(`/ordens/${ORDEM}/trajetoria`));
    expect(pedido.request.method).toBe('GET');
    expect(pedido.request.params.get('limite')).toBe('20');
    expect(pedido.request.params.get('cursor')).toBe(cursor);
    expect(pedido.request.params.keys().sort()).toEqual(['cursor', 'limite']);
    expect(pedido.request.headers.has('Authorization')).toBe(false);
    pedido.flush({ ordem: ORDEM, eventos: [], proximoCursor: null });
  });

  it('limita espera HTTP a oito segundos e cancela pedido pendente', () => {
    vi.useFakeTimers();
    const erro = vi.fn();
    servico.consultar(ORDEM).subscribe({ error: erro });
    const pedido = http.expectOne((p) => p.url.endsWith(`/ordens/${ORDEM}/trajetoria`));
    vi.advanceTimersByTime(7999);
    expect(erro).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(erro).toHaveBeenCalledTimes(1);
    expect(pedido.cancelled).toBe(true);
  });
});
