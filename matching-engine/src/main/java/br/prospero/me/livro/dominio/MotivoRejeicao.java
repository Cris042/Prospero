package br.prospero.me.livro.dominio;

/** Recusa na admissão, sem tocar o livro (ADR-013). */
public enum MotivoRejeicao {
    ATIVO_DESCONHECIDO,
    COMBINACAO_INVALIDA,
    PRECO_ACIMA_DA_RESERVA,
    ORDEM_DUPLICADA,
    LIMITE_ORDENS_ATIVAS
}
