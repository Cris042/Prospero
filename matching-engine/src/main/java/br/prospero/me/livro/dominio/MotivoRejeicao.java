package br.prospero.me.livro.dominio;

/** Recusa na admissão, sem tocar o livro (ADR-013). */
public enum MotivoRejeicao {
    ATIVO_DESCONHECIDO,
    COMBINACAO_INVALIDA,
    PRECO_ACIMA_DA_RESERVA,
    PRECO_INVALIDO,
    QUANTIDADE_INVALIDA,
    SESSAO_ANTERIOR,
    SESSAO_ENCERRADA,
    SESSAO_NAO_ENCERRADA,
    LIMITE_ORDENS_ATIVAS,
    LIMITE_STOPS_PENDENTES
}
