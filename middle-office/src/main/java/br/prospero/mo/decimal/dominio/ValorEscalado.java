package br.prospero.mo.decimal.dominio;

import java.math.BigDecimal;

/** Representação técnica assinada; igualdade inclui unidades e escala. */
public record ValorEscalado(long unidades, int escala) {
    public ValorEscalado {
        exigirEscalaValida(escala);
    }

    public static void exigirEscalaValida(int escala) {
        if (escala < 0 || escala > 18) {
            throw new IllegalArgumentException("Escala de destino fora do limite técnico.");
        }
    }

    public BigDecimal emDecimal() {
        return BigDecimal.valueOf(unidades, escala);
    }
}
