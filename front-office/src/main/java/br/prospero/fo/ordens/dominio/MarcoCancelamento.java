package br.prospero.fo.ordens.dominio;

import java.time.Instant;
import java.util.UUID;

/** Fato do cancelamento manual que entra na trajetória da ordem (solicitado, confirmado ou recusado). */
public record MarcoCancelamento(String origem, String tipo, UUID evento, String assinatura, Instant ocorridoEm,
                                String motivo, String traceparent) {
}
