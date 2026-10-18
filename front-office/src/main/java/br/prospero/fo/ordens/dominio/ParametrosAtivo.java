package br.prospero.fo.ordens.dominio;

import java.math.BigDecimal;
import java.util.Objects;

/** Parâmetros de negociação lidos do catálogo do FO: tick, lote e preço de referência. */
public record ParametrosAtivo(String ticker, BigDecimal tick, long lote, BigDecimal precoReferencia) {
    public ParametrosAtivo {
        Objects.requireNonNull(ticker);
        if (tick.signum() <= 0 || lote <= 0 || precoReferencia.signum() <= 0) {
            throw new IllegalArgumentException("Parâmetros do ativo inválidos.");
        }
    }
}
