package br.prospero.fo.mercado.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class SnapshotMercadoId implements Serializable {
    @Column(nullable = false)
    public UUID namespace;
    @Column(nullable = false, length = 8)
    public String ticker;

    @Override
    public boolean equals(Object outro) {
        return outro instanceof SnapshotMercadoId id && Objects.equals(namespace, id.namespace)
                && Objects.equals(ticker, id.ticker);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, ticker);
    }
}
