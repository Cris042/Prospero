package br.prospero.me.livro.dominio;

/** Por que a ordem deixou o livro (ou nunca repousou nele). */
public enum MotivoEncerramento {
    EXECUTADA,
    SALDO_CANCELADO_IOC,
    FOK_SEM_LIQUIDEZ,
    FORA_DA_FAIXA,
    LIMITADA_PELA_RESERVA,
    SEM_LIQUIDEZ,
    LIMITE_NEGOCIOS,
    EXPIRADA_DIA,
    LIMITE_CASCATA,
    CANCELADA_OCO
}
