package br.prospero.fo.ordens.dominio;

/** Estados da operação; o ranking impede regressão quando os resultados do ME e do MO chegam fora de ordem. */
public enum EstadoCancelamento {
    AGUARDANDO_RISCO(0), SOLICITADO(1), DECIDIDO(2), CONFIRMADO(3), RECUSADO(3);

    private final int ranking;

    EstadoCancelamento(int ranking) {
        this.ranking = ranking;
    }

    public boolean terminal() {
        return ranking == 3;
    }

    public boolean avancaPara(EstadoCancelamento novo) {
        return novo.ranking > ranking;
    }
}
