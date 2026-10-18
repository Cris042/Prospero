package br.prospero.mo.risco.dominio;

import java.math.BigDecimal;
import java.util.Objects;

/** Parâmetros do ativo no MO; versao é registrada em cada decisão. */
public record ParametrosRisco(String ticker, long lote, BigDecimal tick, BigDecimal tunelPercentual,
                              long quantidadeMaxima, int versao) {
    public ParametrosRisco {
        Objects.requireNonNull(ticker);
        Objects.requireNonNull(tick);
        Objects.requireNonNull(tunelPercentual);
    }
}
