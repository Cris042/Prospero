package br.prospero.fo.mercado.dominio;

import java.math.BigDecimal;
import java.util.Objects;

/** Ativo fictício negociável; referência inicial não substitui o último negócio. */
public record Ativo(String ticker, String nome, BigDecimal precoReferencia, BigDecimal tick, long lote) {
    public Ativo {
        Objects.requireNonNull(ticker);
        Objects.requireNonNull(nome);
        if (precoReferencia.signum() <= 0 || tick.signum() <= 0 || lote <= 0) {
            throw new IllegalArgumentException("Parâmetros do ativo inválidos.");
        }
    }
}
