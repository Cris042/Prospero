package br.prospero.fo.ordens.dominio;

/** Catálogo fechado de motivos do risco e matching (ADRs012/013); cada fronteira mantém sua allowlist. */
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
    LIMITE_ORDENS_ATIVAS,
    LIMITE_STOPS_PENDENTES
}
