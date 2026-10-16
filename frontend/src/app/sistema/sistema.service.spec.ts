import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SistemaService, SERVICOS } from './sistema.service';

describe('SistemaService', () => {
  let http: HttpTestingController;
  let service: SistemaService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(SistemaService);
  });

  afterEach(() => http.verify());

  it('exige identidade e readiness antes de anunciar um serviço disponível', () => {
    service.consultar().subscribe((resultados) => {
      expect(resultados).toHaveLength(4);
      expect(resultados.every((item) => item.disponivel)).toBe(true);
    });
    for (const servico of SERVICOS) {
      http.expectOne(`/servicos/${servico.id}/api/v1/sistema`).flush({
        servico: servico.id,
        versao: '0.1.0-SNAPSHOT',
        etapa: 'FUNDAÇÃO',
      });
      http.expectOne(`/servicos/${servico.id}/q/health/ready`).flush({ status: 'UP' });
    }
  });

  it('preserva os outros cartões quando um microsserviço fica indisponível', () => {
    service.consultar().subscribe((resultados) => {
      expect(resultados[0].disponivel).toBe(false);
      expect(resultados.slice(1).every((item) => item.disponivel)).toBe(true);
    });
    SERVICOS.forEach((servico, index) => {
      http.expectOne(`/servicos/${servico.id}/api/v1/sistema`).flush({
        servico: servico.id,
        versao: '0.1.0-SNAPSHOT',
        etapa: 'FUNDAÇÃO',
      });
      const health = http.expectOne(`/servicos/${servico.id}/q/health/ready`);
      if (index === 0) {
        health.flush({ status: 'DOWN' }, { status: 503, statusText: 'Service Unavailable' });
        return;
      }
      health.flush({ status: 'UP' });
    });
  });

  it('não confunde HTTP 200 de identidade com prontidão das dependências', () => {
    service.consultar().subscribe((resultados) => {
      expect(resultados.every((item) => !item.disponivel)).toBe(true);
    });
    for (const servico of SERVICOS) {
      http.expectOne(`/servicos/${servico.id}/api/v1/sistema`).flush({
        servico: servico.id,
        versao: '0.1.0-SNAPSHOT',
        etapa: 'FUNDAÇÃO',
      });
      http.expectOne(`/servicos/${servico.id}/q/health/ready`).flush({ status: 'DOWN' });
    }
  });
});
