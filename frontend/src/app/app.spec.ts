import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { App } from './app';
import { MercadoService } from './mercado/mercado.service';
import { OrdensService } from './ordens/ordens.service';
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

  it('não exibe mais o estado dos serviços na página principal', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const html = fixture.nativeElement as HTMLElement;
    expect(html.querySelector('.componentes')).toBeNull();
    expect(html.textContent).not.toContain('Estado dos serviços');
  });

  it('oferece alternância de tema acessível na barra superior', async () => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const botao = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      '.topbar button.alternar-tema',
    )!;
    expect(botao.textContent).toContain('Tema escuro');
    expect(botao.getAttribute('aria-pressed')).toBe('false');

    botao.click();
    await fixture.whenStable();
    expect(botao.getAttribute('aria-pressed')).toBe('true');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');

    botao.click();
    await fixture.whenStable();
    expect(botao.getAttribute('aria-pressed')).toBe('false');
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });
});
