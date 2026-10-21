package br.prospero.me.livro.dominio;

/** Tipos do núcleo; stops acionados viram Market/Limit. Kafka v1 continua restrito a Market/Limit. */
public enum TipoOrdem {
    MARKET, LIMIT, STOP, STOP_LIMIT
}
