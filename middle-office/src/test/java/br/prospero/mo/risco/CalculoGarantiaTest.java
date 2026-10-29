package br.prospero.mo.risco;

import br.prospero.mo.risco.helper.CalculoGarantia;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-021: garantia exata e rateio que libera tudo, sem resíduo, quando a posição vendida zera. */
class CalculoGarantiaTest {
    @Test
    void garantiaEhTetoVezesParteADescobertoComEscalaDeCentavos() {
        assertEquals(new BigDecimal("33.00"), CalculoGarantia.garantia(new BigDecimal("11.00"), 3));
        assertThrows(IllegalArgumentException.class, () -> CalculoGarantia.garantia(null, 1));
        assertThrows(IllegalArgumentException.class, () -> CalculoGarantia.garantia(new BigDecimal("11.00"), 0));
    }

    @Test
    void rateioSomaExatamenteAGarantiaEmQualquerSequenciaDeParcelas() {
        BigDecimal garantia = new BigDecimal("10.00");
        long total = 3;
        BigDecimal liberado = BigDecimal.ZERO;
        long restante = total;
        BigDecimal saldo = garantia;
        for (long parte : new long[]{1, 1, 1}) {
            BigDecimal parcela = CalculoGarantia.proporcional(saldo, parte, restante);
            liberado = liberado.add(parcela);
            saldo = saldo.subtract(parcela);
            restante -= parte;
        }
        assertEquals(new BigDecimal("3.33").add(new BigDecimal("3.34")).add(new BigDecimal("3.33")), liberado);
        assertEquals(0, saldo.signum(), "nada fica preso no caixa");
        assertEquals(new BigDecimal("22.00"), CalculoGarantia.proporcional(new BigDecimal("22.00"), 2, 2));
        assertEquals(new BigDecimal("0.00"), CalculoGarantia.proporcional(new BigDecimal("22.00"), 0, 2));
        assertThrows(IllegalArgumentException.class, () -> CalculoGarantia.proporcional(new BigDecimal("1.00"), 3, 2));
    }
}
