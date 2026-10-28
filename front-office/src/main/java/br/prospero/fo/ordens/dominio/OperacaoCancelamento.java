package br.prospero.fo.ordens.dominio;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Pedido de cancelamento de uma ordem; decisão é o resultado do ME e motivo, a recusa final do MO. */
public record OperacaoCancelamento(UUID ordem, String conta, EstadoCancelamento estado,
                                   EventoMatching.ResultadoCancelamento decisao, String motivo,
                                   Instant solicitadaEm, Instant atualizadaEm) {
    public OperacaoCancelamento {
        Objects.requireNonNull(ordem);
        Objects.requireNonNull(conta);
        Objects.requireNonNull(estado);
        Objects.requireNonNull(solicitadaEm);
        Objects.requireNonNull(atualizadaEm);
        if ((estado == EstadoCancelamento.RECUSADO) != (motivo != null)) {
            throw new IllegalArgumentException("Motivo existe somente na recusa.");
        }
    }
}
