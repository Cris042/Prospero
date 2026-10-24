package br.prospero.mo.liquidacao.dominio;

import java.util.Objects;

/** Expectativa gravada no consumo do negócio e se já foi creditada. */
public record Expectativa(CreditoEsperado credito, boolean creditada) {
    public Expectativa {
        Objects.requireNonNull(credito);
    }
}
