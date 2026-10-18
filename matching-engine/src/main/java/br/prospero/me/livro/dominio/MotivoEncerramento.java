package br.prospero.me.livro.dominio;

/** Por que a ordem deixou o livro (ou nunca repousou nele). */
public enum MotivoEncerramento {
    EXECUTADA,
    SALDO_CANCELADO_IOC,
    FOK_SEM_LIQUIDEZ,
    FORA_DA_FAIXA,
    SEM_LIQUIDEZ,
    LIMITE_NEGOCIOS
}
