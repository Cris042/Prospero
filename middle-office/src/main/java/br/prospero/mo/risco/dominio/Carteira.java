package br.prospero.mo.risco.dominio;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Recursos de risco da conta: caixa e custódia por ativo, cada um com disponível + reservado = total. */
public record Carteira(String conta, BigDecimal total, BigDecimal disponivel, BigDecimal reservado,
                       List<Custodia> custodias, BigDecimal exposicaoVendida, BigDecimal limiteVendido) {
    public Carteira(String conta, BigDecimal total, BigDecimal disponivel, BigDecimal reservado, List<Custodia> custodias) {
        this(conta, total, disponivel, reservado, custodias, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
    }

    public Carteira {
        Objects.requireNonNull(conta);
        Objects.requireNonNull(total);
        Objects.requireNonNull(disponivel);
        Objects.requireNonNull(reservado);
        custodias = List.copyOf(custodias);
        Objects.requireNonNull(exposicaoVendida);
        Objects.requireNonNull(limiteVendido);
    }

    /** total/disponível/reservado descrevem a parte coberta; o descoberto executado é a custódia a entregar (ADR-021). */
    public record Custodia(String ticker, long total, long disponivel, long reservado, long descobertoReservado,
                           long descobertoExecutado) {
        public Custodia(String ticker, long total, long disponivel, long reservado) {
            this(ticker, total, disponivel, reservado, 0, 0);
        }

        public Custodia {
            Objects.requireNonNull(ticker);
        }
    }
}
