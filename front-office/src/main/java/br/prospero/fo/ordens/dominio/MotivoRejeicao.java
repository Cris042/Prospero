package br.prospero.fo.ordens.dominio;

/** Catálogo fechado de motivos do risco (ADR-012); resultado com motivo fora dele é descartado. */
public enum MotivoRejeicao {
    SALDO_INSUFICIENTE,
    CUSTODIA_INSUFICIENTE,
    QUANTIDADE_MAXIMA_EXCEDIDA,
    QUANTIDADE_FORA_DO_LOTE,
    ATIVO_DESCONHECIDO,
    CONTA_DESCONHECIDA,
    PRECO_BASE_AUSENTE
}
