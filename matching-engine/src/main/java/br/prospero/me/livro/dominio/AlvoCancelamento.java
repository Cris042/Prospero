package br.prospero.me.livro.dominio;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Identidade completa do alvo interno; o solicitante não escolhe preço ou quantidade a retirar. */
public record AlvoCancelamento(UUID ordem, UUID intencao, String conta, String ticker, LocalDate sessao) {
    public AlvoCancelamento {
        Objects.requireNonNull(ordem);
        Objects.requireNonNull(intencao);
        Objects.requireNonNull(conta);
        Objects.requireNonNull(ticker);
        Objects.requireNonNull(sessao);
        if (conta.isBlank() || ticker.isBlank()) {
            throw new IllegalArgumentException("Identidade do alvo inválida.");
        }
    }
}
