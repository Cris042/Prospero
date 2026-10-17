package br.prospero.fo.mercado.dominio;

import java.math.BigDecimal;
import java.util.Objects;

/** Negócio da sessão do snapshot; a ordem da lista define o último. */
public record Negocio(BigDecimal preco, long quantidade) {
    public Negocio {
        Objects.requireNonNull(preco);
    }
}
