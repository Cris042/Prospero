package br.prospero.mo.liquidacao.dominio;

/** Recurso creditado na liquidação: caixa em reais (escala 2) ou custódia em quantidade inteira do ativo. */
public enum RecursoCredito {
    CAIXA(2),
    CUSTODIA(0);

    private final int escala;

    RecursoCredito(int escala) {
        this.escala = escala;
    }

    public int escala() {
        return escala;
    }
}
