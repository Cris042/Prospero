import { TestBed } from '@angular/core/testing';
import { CHAVE_TEMA, TemaService } from './tema.service';

type Ouvinte = (evento: { matches: boolean }) => void;

function simularSistema(escuro: boolean) {
  const ouvintes: Ouvinte[] = [];
  const original = window.matchMedia;
  window.matchMedia = ((consulta: string) => ({
    matches: escuro && consulta === '(prefers-color-scheme: dark)',
    media: consulta,
    addEventListener: (_tipo: string, ouvinte: Ouvinte) => ouvintes.push(ouvinte),
    removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  return {
    mudar: (matches: boolean) => ouvintes.forEach((ouvinte) => ouvinte({ matches })),
    restaurar: () => (window.matchMedia = original),
  };
}

describe('TemaService', () => {
  let sistema: ReturnType<typeof simularSistema> | undefined;

  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
    TestBed.resetTestingModule();
  });

  afterEach(() => {
    sistema?.restaurar();
    sistema = undefined;
    vi.restoreAllMocks();
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  it('segue o tema do sistema quando não há escolha salva', () => {
    sistema = simularSistema(true);
    const servico = TestBed.inject(TemaService);
    expect(servico.tema()).toBe('escuro');
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);

    sistema.mudar(false);
    expect(servico.tema()).toBe('claro');
  });

  it('usa claro quando o navegador não informa preferência', () => {
    sistema = simularSistema(false);
    expect(TestBed.inject(TemaService).tema()).toBe('claro');
  });

  it('alterna, aplica data-theme e persiste a escolha', () => {
    sistema = simularSistema(false);
    const servico = TestBed.inject(TemaService);

    servico.alternar();
    expect(servico.tema()).toBe('escuro');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem(CHAVE_TEMA)).toBe('escuro');

    servico.alternar();
    expect(servico.tema()).toBe('claro');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(localStorage.getItem(CHAVE_TEMA)).toBe('claro');
  });

  it('restaura a escolha salva e deixa de seguir o sistema', () => {
    sistema = simularSistema(true);
    localStorage.setItem(CHAVE_TEMA, 'claro');
    const servico = TestBed.inject(TemaService);
    expect(servico.tema()).toBe('claro');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');

    sistema.mudar(true);
    expect(servico.tema()).toBe('claro');
  });

  it('ignora valor salvo desconhecido', () => {
    sistema = simularSistema(true);
    localStorage.setItem(CHAVE_TEMA, 'roxo');
    expect(TestBed.inject(TemaService).tema()).toBe('escuro');
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
  });

  it('funciona sem localStorage disponível', () => {
    sistema = simularSistema(false);
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('bloqueado', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('bloqueado', 'SecurityError');
    });
    const servico = TestBed.inject(TemaService);
    expect(servico.tema()).toBe('claro');

    expect(() => servico.alternar()).not.toThrow();
    expect(servico.tema()).toBe('escuro');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });
});
