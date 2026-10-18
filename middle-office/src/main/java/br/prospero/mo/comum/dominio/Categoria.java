package br.prospero.mo.comum.dominio;

/** Natureza da violação; a camada REST traduz para o código HTTP. */
public enum Categoria {
    ENTRADA_INVALIDA,
    NAO_ENCONTRADO,
    CONFLITO,
    REGRA_VIOLADA,
    LIMITE_ATINGIDO,
    PROIBIDO
}
