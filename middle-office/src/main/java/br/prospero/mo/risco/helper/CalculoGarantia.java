package br.prospero.mo.risco.helper;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Garantia da venda a descoberto (ADR-021), em BigDecimal exato e com o resto inteiro na última liberação. */
public final class CalculoGarantia {
    private CalculoGarantia() {
    }

    /** Parte a descoberto × teto da base do túnel (o mesmo teto da compra Market). */
    public static BigDecimal garantia(BigDecimal teto, long descoberta) {
        if (teto == null || teto.signum() <= 0 || descoberta <= 0) throw new IllegalArgumentException("Garantia inválida.");
        return teto.multiply(BigDecimal.valueOf(descoberta)).setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * Quanto de {@code garantia} corresponde a {@code parte} de {@code total} unidades: proporcional em centavos
     * (HALF_EVEN) e tudo quando a parte fecha o total, para nunca sobrar resíduo preso no caixa.
     */
    public static BigDecimal proporcional(BigDecimal garantia, long parte, long total) {
        if (garantia == null || garantia.signum() < 0 || parte < 0 || total <= 0 || parte > total) {
            throw new IllegalArgumentException("Rateio de garantia inválido.");
        }
        if (parte == 0) return BigDecimal.ZERO.setScale(2);
        if (parte == total) return garantia.setScale(2, RoundingMode.UNNECESSARY);
        return garantia.multiply(BigDecimal.valueOf(parte)).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_EVEN);
    }
}
