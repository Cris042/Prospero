package br.prospero.mo.risco.dominio;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Recursos de risco da conta: caixa e custódia por ativo, cada um com disponível + reservado = total. */
public record Carteira(String conta, BigDecimal total, BigDecimal disponivel, BigDecimal reservado,
                       List<Custodia> custodias) {
    public Carteira {
        Objects.requireNonNull(conta);
        Objects.requireNonNull(total);
        Objects.requireNonNull(disponivel);
        Objects.requireNonNull(reservado);
        custodias = List.copyOf(custodias);
    }

    public record Custodia(String ticker, long total, long disponivel, long reservado) {
        public Custodia {
            Objects.requireNonNull(ticker);
        }
    }
}
