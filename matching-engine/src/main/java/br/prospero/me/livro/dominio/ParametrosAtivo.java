package br.prospero.me.livro.dominio;

import java.math.BigDecimal;
import java.util.Objects;

/** Parâmetros do ativo no ME: tick, referência (base antes do primeiro negócio) e túnel da faixa da Market. */
public record ParametrosAtivo(String ticker, BigDecimal tick, BigDecimal referencia, BigDecimal tunelPercentual) {
    public ParametrosAtivo {
        Objects.requireNonNull(ticker);
        Objects.requireNonNull(tick);
        Objects.requireNonNull(referencia);
        Objects.requireNonNull(tunelPercentual);
        if (!representacaoLimitada(tick) || !representacaoLimitada(referencia)
                || !representacaoLimitada(tunelPercentual)
                || tick.signum() <= 0 || tick.stripTrailingZeros().scale() > 2 || referencia.signum() <= 0
                || referencia.compareTo(new BigDecimal("1000000.00")) > 0
                || referencia.remainder(tick).signum() != 0 || tunelPercentual.signum() <= 0
                || tunelPercentual.compareTo(new BigDecimal("50")) > 0) {
            throw new IllegalArgumentException("Parâmetros do ativo inválidos: " + ticker);
        }
    }

    /** Guarda de custo antes de comparar, normalizar ou alinhar escalas; não arredonda o valor. */
    static boolean representacaoLimitada(BigDecimal valor) {
        return valor.scale() >= -6 && valor.scale() <= 8 && valor.precision() <= 15;
    }
}
