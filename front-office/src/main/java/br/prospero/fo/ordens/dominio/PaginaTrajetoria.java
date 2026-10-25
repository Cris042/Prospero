package br.prospero.fo.ordens.dominio;

import java.time.Instant;
import java.util.List;

public record PaginaTrajetoria(long revisao, boolean historicoCompleto, Instant disponivelDesde,
                              List<MarcoTrajetoria> eventos) {
    public PaginaTrajetoria {
        eventos = List.copyOf(eventos);
    }
}
