package br.prospero.me.livro.dominio;

/** Tipos do núcleo; stops acionados viram Market/Limit. Kafka v1 continua restrito a Market/Limit. */
public enum TipoOrdem {
    MARKET, LIMIT, STOP, STOP_LIMIT;

    /** Stop e Stop Limit: aguardam gatilho e exigem preço de gatilho. */
    public boolean stop() {
        return this == STOP || this == STOP_LIMIT;
    }

    /** Limit e Stop Limit: exigem preço limite. */
    public boolean comLimite() {
        return this == LIMIT || this == STOP_LIMIT;
    }
}
