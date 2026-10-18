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
    }
}
