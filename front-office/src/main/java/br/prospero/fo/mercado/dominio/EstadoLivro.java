package br.prospero.fo.mercado.dominio;

/** Estados distintos: sem snapshot não equivale a livro preparado e vazio. */
public enum EstadoLivro {
    SEM_DADOS,
    VAZIO,
    SOMENTE_COMPRA,
    SOMENTE_VENDA,
    COMPLETO
}
