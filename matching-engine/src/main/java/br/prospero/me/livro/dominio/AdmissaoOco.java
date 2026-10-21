package br.prospero.me.livro.dominio;

import java.util.Objects;

/**
 * Par OCO (ADR-010/013): Take Profit Limit DIA e Stop Loss Stop/Stop Limit DIA com a mesma reserva. O núcleo repete as
 * guardas de coerência do par antes de qualquer mutação.
 */
public record AdmissaoOco(Admissao takeProfit, Admissao stopLoss) {
    public AdmissaoOco {
        Objects.requireNonNull(takeProfit);
        Objects.requireNonNull(stopLoss);
    }
}
