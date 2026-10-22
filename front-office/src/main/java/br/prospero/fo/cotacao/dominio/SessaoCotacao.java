package br.prospero.fo.cotacao.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Acumuladores exatos de um ativo/sessão; último e VWAP são ausentes sem negócio, nunca zero. */
public record SessaoCotacao(String ticker, LocalDate sessao, BigDecimal ultimoPreco, long volumeQuantidade,
                            BigDecimal volumeFinanceiro, long revisao, Instant atualizadoEm, boolean encerrada) {
    public static final int ESCALA_VWAP = 4;

    public SessaoCotacao {
        Objects.requireNonNull(ticker);
        Objects.requireNonNull(sessao);
        Objects.requireNonNull(volumeFinanceiro);
        Objects.requireNonNull(atualizadoEm);
    }

    public BigDecimal vwap() {
        return volumeQuantidade == 0 ? null
                : volumeFinanceiro.divide(BigDecimal.valueOf(volumeQuantidade), ESCALA_VWAP, RoundingMode.HALF_EVEN);
    }
}
