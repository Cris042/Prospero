package br.prospero.fo.ordens.dominio;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Resposta final do MO ao cancelamento: CONFIRMADO (sem motivo) ou RECUSADO com um dos cinco motivos fechados. */
public record ResultadoCancelamento(UUID evento, UUID ordem, String conta, boolean confirmado, String motivo,
                                    Instant ocorridoEm, String traceparent, String assinatura) implements MensagemRisco {
    public ResultadoCancelamento {
        Objects.requireNonNull(evento);
        Objects.requireNonNull(ordem);
        Objects.requireNonNull(conta);
        if (confirmado != (motivo == null)) {
            throw new IllegalArgumentException("Motivo existe somente na recusa.");
        }
    }
}
