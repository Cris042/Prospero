package br.prospero.mo.risco.dominio;

/** Catálogo fechado de motivos (ADR-012); também é label de métrica, nunca texto livre. */
public enum MotivoRejeicao {
    SALDO_INSUFICIENTE,
    CUSTODIA_INSUFICIENTE,
    QUANTIDADE_MAXIMA_EXCEDIDA,
    QUANTIDADE_FORA_DO_LOTE,
    ATIVO_DESCONHECIDO,
    CONTA_DESCONHECIDA,
    PRECO_BASE_AUSENTE,
    LIMITE_POSICAO_EXCEDIDO,
    EXPOSICAO_VENDIDA_EXCEDIDA
}
