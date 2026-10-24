import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { HistoricoService } from './historico.service';

describe('Consulta HTTP do histórico', () => {
  let http: HttpTestingController;
  let servico: HistoricoService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    servico = TestBed.inject(HistoricoService);
  });

  afterEach(() => http.verify());

  it('consulta negócios e razão por GET com vinte itens e sem credencial no navegador', () => {
    servico.negocios().subscribe();
    servico.razao().subscribe();
    for (const recurso of ['negocios', 'razao']) {
      const pedido = http.expectOne((p) => p.url === `/servicos/back-office/api/v1/${recurso}`);
      expect(pedido.request.method).toBe('GET');
      expect(pedido.request.params.get('limite')).toBe('20');
      expect(pedido.request.params.has('cursor')).toBe(false);
      expect(pedido.request.headers.has('Authorization')).toBe(false);
      pedido.flush({
        conta: 'CONTA-001',
        [recurso === 'razao' ? 'lancamentos' : recurso]: [],
        proximoCursor: null,
      });
    }
  });

  it('transporta o cursor opaco como parâmetro sem interpolar outro parâmetro ou rota', () => {
    const cursor = 'opaco+/?&conta=CONTA-999';
    servico.negocios(cursor).subscribe();
    const pedido = http.expectOne((p) => p.url === '/servicos/back-office/api/v1/negocios');
    expect(pedido.request.params.get('cursor')).toBe(cursor);
    expect(pedido.request.params.keys()).toEqual(['limite', 'cursor']);
    expect(pedido.request.urlWithParams).toContain('cursor=opaco%2B/?%26conta=CONTA-999');
    pedido.flush({ conta: 'CONTA-001', negocios: [], proximoCursor: null });
  });
});
