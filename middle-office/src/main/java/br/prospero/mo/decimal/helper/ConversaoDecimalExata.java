package br.prospero.mo.decimal.helper;

import br.prospero.mo.decimal.dominio.RegraDeSinal;
import br.prospero.mo.decimal.dominio.ValorEscalado;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Conversão técnica limitada, exata e sem conhecimento de políticas financeiras. */
public final class ConversaoDecimalExata {
    private ConversaoDecimalExata() {
    }

    public static ValorEscalado converter(BigDecimal valor, int escala, RegraDeSinal regra) {
        exigirEntradas(valor, regra);
        ValorEscalado.exigirEscalaValida(escala);
        exigirLimites(valor);
        exigirSinal(valor.signum(), regra);
        return new ValorEscalado(unidadesExatas(ajustarEscala(valor, escala)), escala);
    }

    private static void exigirEntradas(BigDecimal valor, RegraDeSinal regra) {
        if (valor == null || regra == null) {
            throw new IllegalArgumentException("Entrada ou regra de sinal ausente.");
        }
    }

    private static void exigirLimites(BigDecimal valor) {
        if (valor.scale() < -18 || valor.scale() > 18) {
            throw new IllegalArgumentException("Escala de entrada fora do limite técnico.");
        }
        if (valor.unscaledValue().bitLength() > 128) {
            throw new IllegalArgumentException("Precisão de entrada fora do limite técnico.");
        }
        if (valor.precision() > 38) {
            throw new IllegalArgumentException("Precisão de entrada fora do limite técnico.");
        }
    }

    private static void exigirSinal(int sinal, RegraDeSinal regra) {
        if (regra == RegraDeSinal.NAO_NEGATIVO && sinal < 0) {
            throw new IllegalArgumentException("Sinal negativo não permitido.");
        }
        if (regra == RegraDeSinal.POSITIVO && sinal <= 0) {
            throw new IllegalArgumentException("Sinal deve ser estritamente positivo.");
        }
    }

    private static BigDecimal ajustarEscala(BigDecimal valor, int escala) {
        try {
            return valor.setScale(escala, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException causa) {
            throw new IllegalArgumentException("Fração não representável na escala de destino.");
        }
    }

    private static long unidadesExatas(BigDecimal valor) {
        try {
            return valor.unscaledValue().longValueExact();
        } catch (ArithmeticException causa) {
            throw new IllegalArgumentException("Overflow: unidades fora do limite de long.");
        }
    }
}
