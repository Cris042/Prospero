import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { App } from './app';
import { MercadoService } from './mercado/mercado.service';
import { OrdensService } from './ordens/ordens.service';
import { SistemaService } from './sistema/sistema.service';
import { PosicoesService } from './posicoes/posicoes.service';

describe('Painel de fundação', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        {
          provide: PosicoesService,
          useValue: {
            consultar: () =>
              of({
                conta: 'CONTA-001',
                base: 'NEGOCIOS_DESDE_ORIGEM',
                sincronizado: true,
                posicoes: [],
              }),
          },
        },
        { provide: MercadoService, useValue: { listar: () => of([]), livro: () => of(null) } },
        {
          provide: OrdensService,
          useValue: { listar: () => of({ ordens: [], proximoCursor: null }) },
        },
        {
          provide: SistemaService,
          useValue: {
            consultar: () =>
              of([
                {
                  id: 'front-office',
                  nome: 'Front office',
                  papel: 'Ordens',
                  porta: 8081,
                  disponivel: false,
                  versao: '—',
                  etapa: 'Não foi possível consultar',
                },
              ]),
          },
        },
      ],
    }).compileComponents();
  });

  it('apresenta Próspero e informa o estágio real do projeto', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const html = fixture.nativeElement as HTMLElement;
    expect(html.querySelector('h1')?.textContent).toContain('Próspero');
    expect(html.textContent).toContain('Liquidação ainda não implementada');
  });

  it('mostra indisponibilidade por texto e oferece nova consulta', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const html = fixture.nativeElement as HTMLElement;
    expect(html.textContent).toContain('Indisponível');
    expect(html.textContent).toContain('Atualizar');
  });
});
