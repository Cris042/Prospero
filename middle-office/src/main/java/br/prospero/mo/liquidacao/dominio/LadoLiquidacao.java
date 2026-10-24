package br.prospero.mo.liquidacao.dominio;

/** Lado do negócio na liquidação (ADR-016): comprador recebe custódia, vendedor recebe caixa. */
public enum LadoLiquidacao {
    COMPRADOR(RecursoCredito.CUSTODIA),
    VENDEDOR(RecursoCredito.CAIXA);

    private final RecursoCredito recurso;

    LadoLiquidacao(RecursoCredito recurso) {
        this.recurso = recurso;
    }

    public RecursoCredito recurso() {
        return recurso;
    }
}
