package br.prospero.mo.mensageria.dominio;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Linha da outbox: publicada depois do commit pelo relay, pelo menos uma vez. */
public record MensagemSaida(UUID id, String topico, String chave, String tipo, String payload, String traceparent,
                            Instant criadaEm) {
    public MensagemSaida {
        Objects.requireNonNull(id);
        Objects.requireNonNull(topico);
        Objects.requireNonNull(chave);
        Objects.requireNonNull(tipo);
        Objects.requireNonNull(payload);
        Objects.requireNonNull(criadaEm);
    }
}
