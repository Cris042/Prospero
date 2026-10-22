package br.prospero.fo.ordens.dominio;

/** Tipos aceitos na RF02. */
public enum TipoOrdem {
    MARKET,
    LIMIT,
    STOP,
    STOP_LIMIT;

    /** Stop e Stop Limit: aguardam gatilho e exigem preço de gatilho. */
    public boolean stop() {
        return this == STOP || this == STOP_LIMIT;
    }

    /** Limit e Stop Limit: exigem preço limite. */
    public boolean comLimite() {
        return this == LIMIT || this == STOP_LIMIT;
    }
}
