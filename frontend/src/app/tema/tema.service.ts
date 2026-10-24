import { DOCUMENT } from '@angular/common';
import { inject, Injectable, signal } from '@angular/core';

export type Tema = 'claro' | 'escuro';

/** Chave também lida pelo script inline do index.html, que aplica o tema antes do bootstrap. */
export const CHAVE_TEMA = 'prospero-tema';
const CONSULTA_ESCURO = '(prefers-color-scheme: dark)';
const ATRIBUTO: Record<Tema, string> = { claro: 'light', escuro: 'dark' };

/**
 * Sem escolha salva, acompanha o tema do sistema. Ao alternar, fixa `data-theme` no <html> e
 * persiste a escolha; falhas de localStorage não impedem a troca na sessão atual.
 */
@Injectable({ providedIn: 'root' })
export class TemaService {
  private readonly documento = inject(DOCUMENT);
  private readonly consulta = this.documento.defaultView?.matchMedia?.(CONSULTA_ESCURO);
  private escolhido = false;
  readonly tema = signal<Tema>(this.inicial());

  constructor() {
    this.consulta?.addEventListener?.('change', (evento) => {
      if (!this.escolhido) this.tema.set(evento.matches ? 'escuro' : 'claro');
    });
  }

  alternar(): void {
    const proximo: Tema = this.tema() === 'escuro' ? 'claro' : 'escuro';
    this.escolhido = true;
    this.tema.set(proximo);
    this.documento.documentElement.setAttribute('data-theme', ATRIBUTO[proximo]);
    try {
      this.documento.defaultView?.localStorage.setItem(CHAVE_TEMA, proximo);
    } catch {
      // Armazenamento bloqueado: a escolha vale apenas nesta sessão.
    }
  }

  private inicial(): Tema {
    const salvo = this.lerSalvo();
    if (salvo) {
      this.escolhido = true;
      this.documento.documentElement.setAttribute('data-theme', ATRIBUTO[salvo]);
      return salvo;
    }
    return this.consulta?.matches ? 'escuro' : 'claro';
  }

  private lerSalvo(): Tema | null {
    try {
      const valor = this.documento.defaultView?.localStorage.getItem(CHAVE_TEMA);
      return valor === 'claro' || valor === 'escuro' ? valor : null;
    } catch {
      return null;
    }
  }
}
