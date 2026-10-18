package br.prospero.fo.ordens.dominio;

/** Catálogo fechado de motivos do risco (ADR-012); resultado com motivo fora dele é descartado. */
public enum MotivoRejeicao {
    SALDO_INSUFICIENTE,
    CUSTODIA_INSUFICIENTE,
    QUANTIDADE_MAXIMA_EXCEDIDA,
    QUANTIDADE_FORA_DO_LOTE,
    ATIVO_DESCONHECIDO,
    CONTA_DESCONHECIDA,
    PRECO_BASE_AUSENTE,
    COMBINACAO_INVALIDA,
    PRECO_ACIMA_DA_RESERVA,
    PRECO_INVALIDO,
    QUANTIDADE_INVALIDA,
    SESSAO_ANTERIOR,
    LIMITE_ORDENS_ATIVAS
}
