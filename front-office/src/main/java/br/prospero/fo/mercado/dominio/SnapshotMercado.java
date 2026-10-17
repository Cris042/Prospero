package br.prospero.fo.mercado.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Snapshot integral e validado de um ativo em um namespace; substituído atomicamente. */
public record SnapshotMercado(UUID namespace, String ticker, long revisao, LocalDate sessao,
                              Instant preparadoEm, List<Oferta> ofertas, List<Negocio> negocios) {
    public SnapshotMercado {
        Objects.requireNonNull(namespace);
        Objects.requireNonNull(ticker);
        Objects.requireNonNull(sessao);
        Objects.requireNonNull(preparadoEm);
        ofertas = List.copyOf(ofertas);
        negocios = List.copyOf(negocios);
    }
}
