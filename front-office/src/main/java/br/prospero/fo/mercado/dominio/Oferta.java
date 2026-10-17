package br.prospero.fo.mercado.dominio;

import java.math.BigDecimal;
import java.util.Objects;

/** Oferta individual em repouso no snapshot sintético. */
public record Oferta(Lado lado, BigDecimal preco, long quantidade) {
    public Oferta {
        Objects.requireNonNull(lado);
        Objects.requireNonNull(preco);
    }
}
