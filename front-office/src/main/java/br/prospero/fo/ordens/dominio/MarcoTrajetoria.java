package br.prospero.fo.ordens.dominio;

import java.time.Instant;
import java.util.UUID;

/** Fato observado; sequência local de confirmação, sem ordenar relógios dos serviços. */
public record MarcoTrajetoria(long sequencia, String origem, String tipo, UUID evento, UUID causacao,
                              String negocio, Instant ocorridoEm, Instant observadoEm, String quantidade,
                              String motivo, String traceId) {
}
