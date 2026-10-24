package br.prospero.mo.liquidacao.dominio;

/** Motivos enumerados e finitos: viram label de métrica e coluna, nunca carregam conteúdo do evento. */
public enum MotivoRecusa {
    /** Envelope, tamanho, campos, tipos ou formatos fora do contrato v1. */
    CONTRATO_INVALIDO,
    /** Origem diferente de back-office. */
    ORIGEM_INVALIDA,
    /** id diferente do derivado de negócio e lado. */
    ID_INCOERENTE,
    /** Chave Kafka diferente da conta do evento. */
    CHAVE_INCOERENTE,
    /** Conta, recurso, ativo ou valor diferentes da expectativa gravada no consumo do negócio. */
    DIVERGENTE,
    /** Mesmo id de um crédito já aplicado, com assinatura diferente. */
    REPETICAO_DIVERGENTE,
    /** Sem expectativa e com o volume aguardando no teto configurado. */
    AGUARDANDO_ESGOTADO
}
