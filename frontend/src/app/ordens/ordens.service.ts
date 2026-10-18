import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, timeout } from 'rxjs';

export type Lado = 'COMPRA' | 'VENDA';
export type Tipo = 'MARKET' | 'LIMIT' | 'STOP' | 'STOP_LIMIT';
export type Validade = 'DIA' | 'IOC' | 'FOK';

/** Matriz aceita no ADR-010; o backend continua sendo a fonte de verdade. */
export const VALIDADES: Record<Tipo, readonly Validade[]> = {
  MARKET: ['IOC', 'FOK'],
  LIMIT: ['DIA', 'IOC', 'FOK'],
  STOP: ['DIA'],
  STOP_LIMIT: ['DIA'],
};

export const exigeLimite = (tipo: Tipo) => tipo === 'LIMIT' || tipo === 'STOP_LIMIT';
export const exigeGatilho = (tipo: Tipo) => tipo === 'STOP' || tipo === 'STOP_LIMIT';

export interface PedidoOrdem {
  readonly ticker: string;
  readonly lado: Lado;
  readonly tipo: Tipo;
  readonly validade: Validade;
  readonly quantidade: number;
  readonly precoLimite: string | null;
  readonly precoGatilho: string | null;
}

export interface PedidoOco {
  readonly ticker: string;
  readonly lado: Lado;
  readonly quantidade: number;
  readonly validade: 'DIA';
  readonly takeProfit: { readonly precoLimite: string };
  readonly stopLoss: {
    readonly tipo: 'STOP' | 'STOP_LIMIT';
    readonly precoGatilho: string;
    readonly precoLimite: string | null;
  };
}

export interface Ordem {
  readonly id: string;
  readonly ticker: string;
  readonly lado: Lado;
  readonly tipo: Tipo;
  readonly validade: Validade;
  readonly quantidade: number;
  readonly precoLimite: string | null;
  readonly precoGatilho: string | null;
  readonly grupoOco: string | null;
  readonly papelOco: 'TAKE_PROFIT' | 'STOP_LOSS' | null;
  readonly estado: 'RECEBIDA';
  readonly dataSessao: string;
  readonly recebidaEm: string;
}

export interface Pagina {
  readonly ordens: readonly Ordem[];
  readonly proximoCursor: string | null;
}

const BASE = '/servicos/front-office/api/v1';

const MENSAGENS: Record<string, string> = {
  COMBINACAO_INVALIDA: 'Validade não permitida para este tipo de ordem.',
  CAMPO_EXIGIDO_PELO_TIPO: 'Informe os preços exigidos pelo tipo de ordem.',
  CAMPO_PROIBIDO_PELO_TIPO: 'Há um preço que não se aplica a este tipo de ordem.',
  PRECO_INVALIDO: 'Preço inválido: use até duas casas decimais, entre 0,01 e 1.000.000,00.',
  QUANTIDADE_INVALIDA: 'Quantidade deve ser inteira, entre 1 e 1.000.000.',
  ATIVO_DESCONHECIDO: 'Ativo não cadastrado.',
  OCO_PRECOS_INCOERENTES: 'No OCO de venda o alvo deve ficar acima do stop; na compra, abaixo.',
  OCO_TIPO_INVALIDO: 'O Stop Loss do OCO deve ser Stop ou Stop Limit.',
  IDEMPOTENCIA_CONFLITO: 'Este envio já foi usado com outros dados. Revise e envie de novo.',
  LIMITE_ORDENS: 'Limite de 1.000 ordens por sessão atingido.',
  JSON_INVALIDO: 'Dados da ordem em formato inválido.',
  CORPO_INVALIDO: 'Preencha todos os campos obrigatórios.',
};

/** Aceita vírgula decimal (pt-BR) convertendo para o decimal textual da API. */
export function normalizarPreco(texto: string): string {
  return texto.trim().replace(',', '.');
}

/** Traduz Problem Details em mensagem; distingue erro de rede (repetível com a mesma chave). */
export function mensagemErro(erro: unknown): { texto: string; repetivel: boolean } {
  if (erro instanceof HttpErrorResponse && (erro.status === 401 || erro.status === 403)) {
    return {
      texto:
        'Credencial da conta demo inválida ou expirada. Rode python3 scripts/preparar-ambiente.py e recrie o frontend.',
      repetivel: false,
    };
  }
  if (erro instanceof HttpErrorResponse && erro.status === 429 && !erro.error?.codigo) {
    return {
      texto: 'Muitos envios em sequência. Aguarde um instante e tente de novo.',
      repetivel: true,
    };
  }
  if (erro instanceof HttpErrorResponse && erro.status > 0 && erro.status < 500) {
    const codigo = (erro.error as { codigo?: string } | null)?.codigo;
    return {
      texto: (codigo && MENSAGENS[codigo]) ?? 'Não foi possível registrar a ordem.',
      repetivel: false,
    };
  }
  return {
    texto: 'Falha de comunicação. Tente novamente: o reenvio não duplica a ordem.',
    repetivel: true,
  };
}

@Injectable({ providedIn: 'root' })
export class OrdensService {
  private readonly http = inject(HttpClient);

  enviar(pedido: PedidoOrdem, chave: string): Observable<Ordem> {
    return this.http
      .post<Ordem>(`${BASE}/ordens`, pedido, { headers: this.chave(chave) })
      .pipe(timeout(8000));
  }

  enviarOco(pedido: PedidoOco, chave: string): Observable<{ grupoOco: string; ordens: Ordem[] }> {
    return this.http
      .post<{ grupoOco: string; ordens: Ordem[] }>(`${BASE}/ordens-oco`, pedido, {
        headers: this.chave(chave),
      })
      .pipe(timeout(8000));
  }

  listar(limite = 20): Observable<Pagina> {
    return this.http.get<Pagina>(`${BASE}/ordens?limite=${limite}`).pipe(timeout(8000));
  }

  private chave(chave: string): HttpHeaders {
    return new HttpHeaders({ 'X-Idempotency-Key': chave });
  }
}
