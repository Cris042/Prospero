import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, timeout } from 'rxjs';
import { formatarDecimal } from '../mercado/mercado.service';

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

export type MotivoRejeicao =
  | 'SALDO_INSUFICIENTE'
  | 'CUSTODIA_INSUFICIENTE'
  | 'QUANTIDADE_MAXIMA_EXCEDIDA'
  | 'QUANTIDADE_FORA_DO_LOTE'
  | 'ATIVO_DESCONHECIDO'
  | 'CONTA_DESCONHECIDA'
  | 'PRECO_BASE_AUSENTE'
  | 'LIMITE_STOPS_PENDENTES'
  | 'LIMITE_POSICAO_EXCEDIDO'
  | 'EXPOSICAO_VENDIDA_EXCEDIDA'
  | 'SESSAO_ENCERRADA'
  | 'SESSAO_NAO_ENCERRADA';

export type EstadoCancelamento =
  'AGUARDANDO_RISCO' | 'SOLICITADO' | 'DECIDIDO' | 'CONFIRMADO' | 'RECUSADO';

/** Cancelamento manual (RF10): 202 só confirma a persistência; o saldo é liberado pelo middle-office. */
export interface ResumoCancelamento {
  readonly estado: EstadoCancelamento;
  readonly decisao: string | null;
  readonly motivo: string | null;
}

export interface OperacaoCancelamento extends ResumoCancelamento {
  readonly ordem: string;
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
  /** RECEBIDA aguarda o risco; RESERVADA não implica execução (RF04, ADR-012). */
  readonly estado:
    | 'RECEBIDA'
    | 'RESERVADA'
    | 'REJEITADA'
    | 'AGUARDANDO_GATILHO'
    | 'ABERTA'
    | 'PARCIALMENTE_EXECUTADA'
    | 'EXECUTADA'
    | 'CANCELADA';
  readonly motivoRejeicao: MotivoRejeicao | null;
  /** Motivo terminal do matching, por exemplo CANCELADA_OCO na perna irmã (RF05c.3b). */
  readonly motivoEncerramento?: string | null;
  /** Quantidade retirada do Stop Loss OCO pela execução do Take Profit. */
  readonly quantidadeReduzida?: number;
  readonly dataSessao: string;
  readonly recebidaEm: string;
  readonly cancelamento?: ResumoCancelamento | null;
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
  QUANTIDADE_FORA_DO_LOTE: 'Quantidade deve ser múltipla do lote do ativo.',
  PRECO_FORA_DO_TICK: 'Preço deve ser múltiplo do tick do ativo.',
  STOP_LIMIT_INCOERENTE:
    'No Stop Limit de venda o limite não pode passar do disparo; na compra, não pode ficar abaixo.',
  PRECO_FORA_DO_TUNEL: 'Preço fora do túnel permitido para o ativo.',
  IDEMPOTENCIA_CONFLITO: 'Este envio já foi usado com outros dados. Revise e envie de novo.',
  LIMITE_ORDENS: 'Limite de 1.000 ordens por sessão atingido.',
  JSON_INVALIDO: 'Dados da ordem em formato inválido.',
  CORPO_INVALIDO: 'Preencha todos os campos obrigatórios.',
};

const MENSAGENS_CANCELAMENTO: Record<string, string> = {
  ORDEM_OCO_NAO_CANCELAVEL: 'Ordens de um grupo OCO não podem ser canceladas manualmente.',
  ORDEM_NAO_ENCONTRADA: 'Ordem não encontrada para esta conta.',
  CANCELAMENTO_INDISPONIVEL:
    'Cancelamento temporariamente indisponível. Tente novamente em instantes.',
  JSON_INVALIDO: 'Pedido de cancelamento inválido.',
  CORPO_CANCELAMENTO_INVALIDO: 'Pedido de cancelamento inválido.',
};

/** Mensagem do cancelamento; a repetição do PUT é segura (devolve a mesma operação). */
export function mensagemErroCancelamento(erro: unknown): string {
  if (erro instanceof HttpErrorResponse && (erro.status === 401 || erro.status === 403)) {
    return 'Credencial da conta demo inválida ou expirada. Rode python3 scripts/preparar-ambiente.py e recrie o frontend.';
  }
  if (
    erro instanceof HttpErrorResponse &&
    erro.status > 0 &&
    erro.status < 600 &&
    erro.status !== 502
  ) {
    const codigo = (erro.error as { codigo?: string } | null)?.codigo;
    return (
      (codigo && MENSAGENS_CANCELAMENTO[codigo]) ?? 'Não foi possível solicitar o cancelamento.'
    );
  }
  return 'Falha de comunicação. Tente cancelar de novo: a repetição não duplica o pedido.';
}

/** Aceita vírgula decimal (pt-BR) convertendo para o decimal textual da API. */
export function normalizarPreco(texto: string): string {
  return texto.trim().replace(',', '.');
}

/** Detalhes públicos do Problem Details (faixa do túnel, lote, tick) formatados em pt-BR. */
function complemento(detalhes: Record<string, string> | undefined): string {
  if (!detalhes) return '';
  if (detalhes['minimo'] && detalhes['maximo']) {
    return ` Faixa aceita: ${formatarDecimal(detalhes['minimo'])} a ${formatarDecimal(detalhes['maximo'])}.`;
  }
  if (detalhes['lote']) return ` Lote: ${detalhes['lote']}.`;
  if (detalhes['tick']) return ` Tick: ${formatarDecimal(detalhes['tick'])}.`;
  return '';
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
    const problema = erro.error as { codigo?: string; detalhes?: Record<string, string> } | null;
    const base =
      (problema?.codigo && MENSAGENS[problema.codigo]) ?? 'Não foi possível registrar a ordem.';
    return { texto: base + complemento(problema?.detalhes), repetivel: false };
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

  /** PUT sem corpo na URI canônica; repetir devolve a mesma operação. */
  cancelar(ordem: string): Observable<OperacaoCancelamento> {
    return this.http
      .put<OperacaoCancelamento>(`${BASE}/ordens/${ordem}/cancelamento`, null)
      .pipe(timeout(8000));
  }

  private chave(chave: string): HttpHeaders {
    return new HttpHeaders({ 'X-Idempotency-Key': chave });
  }
}
