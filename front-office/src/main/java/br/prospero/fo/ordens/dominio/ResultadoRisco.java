package br.prospero.fo.ordens.dominio;

import java.util.Objects;
import java.util.UUID;

/** Resposta do MO para uma intenção (grupoEnvio): RESERVADA sem motivo ou REJEITADA com motivo. */
public record ResultadoRisco(UUID evento, UUID grupoEnvio, String conta, EstadoOrdem estado, MotivoRejeicao motivo,
                             java.time.Instant ocorridoEm, String traceparent, String assinatura) {
    public ResultadoRisco(UUID evento, UUID grupoEnvio, String conta, EstadoOrdem estado, MotivoRejeicao motivo) {
        this(evento, grupoEnvio, conta, estado, motivo, null, null, null);
    }

    public ResultadoRisco {
        Objects.requireNonNull(evento);
        Objects.requireNonNull(grupoEnvio);
        Objects.requireNonNull(conta);
        if (estado != EstadoOrdem.RESERVADA && estado != EstadoOrdem.REJEITADA) {
            throw new IllegalArgumentException("Resultado do risco é RESERVADA ou REJEITADA.");
        }
        if ((estado == EstadoOrdem.REJEITADA) != (motivo != null)) {
            throw new IllegalArgumentException("Motivo existe somente na rejeição.");
        }
    }
}
