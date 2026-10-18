package br.prospero.me.livro.helper;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Faixa da Market (ADR-011/013): limites inclusivos do túnel arredondados ao tick para dentro, em BigDecimal. */
public final class FaixaPreco {
    private static final BigDecimal CEM = new BigDecimal("100");

    private FaixaPreco() {
    }

    /** ⌊base × (1 + túnel) / tick⌋ × tick. */
    public static BigDecimal teto(BigDecimal base, BigDecimal tick, BigDecimal tunelPercentual) {
        return arredondar(base.multiply(CEM.add(tunelPercentual)).divide(CEM), tick, RoundingMode.FLOOR);
    }

    /** ⌈base × (1 − túnel) / tick⌉ × tick. */
    public static BigDecimal piso(BigDecimal base, BigDecimal tick, BigDecimal tunelPercentual) {
        return arredondar(base.multiply(CEM.subtract(tunelPercentual)).divide(CEM), tick, RoundingMode.CEILING);
    }

    private static BigDecimal arredondar(BigDecimal valor, BigDecimal tick, RoundingMode modo) {
        return valor.divide(tick, 0, modo).multiply(tick).setScale(2, RoundingMode.UNNECESSARY);
    }
}
