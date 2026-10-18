package br.prospero.me.livro.dominio;

public enum Lado {
    COMPRA, VENDA;

    public Lado oposto() {
        return this == COMPRA ? VENDA : COMPRA;
    }
}
