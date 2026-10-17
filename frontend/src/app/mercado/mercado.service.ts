import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, timeout } from 'rxjs';

export type EstadoLivro = 'SEM_DADOS' | 'VAZIO' | 'SOMENTE_COMPRA' | 'SOMENTE_VENDA' | 'COMPLETO';

/** Decimais chegam como texto e nunca são convertidos para number. */
export interface AtivoResumo {
  readonly ticker: string;
  readonly nome: string;
  readonly precoReferencia: string;
  readonly ultimoPreco: string | null;
  readonly variacao: string | null;
  readonly variacaoPercentual: string | null;
  readonly melhorCompra: string | null;
  readonly melhorVenda: string | null;
  readonly spread: string | null;
  readonly volumeQuantidade: number | null;
  readonly volumeFinanceiro: string | null;
  readonly estadoLivro: EstadoLivro;
  readonly preparadoEm: string | null;
  readonly defasado: boolean;
  readonly origem: string | null;
}

export interface Nivel {
  readonly preco: string;
  readonly quantidade: number;
  readonly ofertas: number;
  readonly quantidadeAcumulada: number;
}

export interface LadoLivro {
  readonly niveis: readonly Nivel[];
  readonly niveisTotal: number;
  readonly quantidadeTotal: number;
}

/** Resposta autossuficiente: selos e níveis vêm da mesma revisão do snapshot. */
export interface Livro {
  readonly ticker: string;
  readonly estadoLivro: EstadoLivro;
  readonly compra: LadoLivro | null;
  readonly venda: LadoLivro | null;
  readonly revisao: number | null;
  readonly sessao: string | null;
  readonly preparadoEm: string | null;
  readonly defasado: boolean;
  readonly origem: string | null;
}

const BASE = '/servicos/front-office/api/v1/ativos';

@Injectable({ providedIn: 'root' })
export class MercadoService {
  private readonly http = inject(HttpClient);

  listar(): Observable<AtivoResumo[]> {
    return this.http.get<AtivoResumo[]>(BASE).pipe(timeout(8000));
  }

  livro(ticker: string): Observable<Livro> {
    return this.http.get<Livro>(`${BASE}/${encodeURIComponent(ticker)}/livro`).pipe(timeout(8000));
  }
}

/** Formata decimal textual em pt-BR por manipulação de texto, sem ponto flutuante. */
export function formatarDecimal(texto: string | null): string {
  if (texto === null) return '—';
  const negativo = texto.startsWith('-');
  const [inteiro, fracao = ''] = (negativo ? texto.slice(1) : texto).split('.');
  const agrupado = inteiro.replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  return (negativo ? '−' : '') + agrupado + (fracao ? ',' + fracao : '');
}

export function formatarVariacao(texto: string | null, sufixo = ''): string {
  if (texto === null) return '—';
  const sinal = texto.startsWith('-') || /^0(\.0+)?$/.test(texto) ? '' : '+';
  return sinal + formatarDecimal(texto) + sufixo;
}

/** Data ISO (aaaa-mm-dd) em dd/mm/aaaa, sem Date nem fuso do navegador. */
export function formatarData(texto: string | null): string {
  if (texto === null) return '—';
  const [ano, mes, dia] = texto.split('-');
  return `${dia}/${mes}/${ano}`;
}

export function formatarInteiro(valor: number | null): string {
  return valor === null ? '—' : formatarDecimal(String(valor));
}
