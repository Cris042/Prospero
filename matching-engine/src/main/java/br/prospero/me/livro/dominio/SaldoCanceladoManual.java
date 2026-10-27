package br.prospero.me.livro.dominio;

import java.util.Objects;
import java.util.UUID;

/** Fato interno da retirada. Não pertence ao catálogo Evento e não é serializável pelos emissores atuais. */
public record SaldoCanceladoManual(UUID ordem, String ticker, long executada, long cancelada) {
    public SaldoCanceladoManual {
        Objects.requireNonNull(ordem);
        Objects.requireNonNull(ticker);
        if (ticker.isBlank() || executada < 0 || executada > MotorMatching.MAX_QUANTIDADE
                || cancelada <= 0 || cancelada > MotorMatching.MAX_QUANTIDADE - executada) {
            throw new IllegalArgumentException("Saldo de cancelamento inválido.");
        }
    }
}
