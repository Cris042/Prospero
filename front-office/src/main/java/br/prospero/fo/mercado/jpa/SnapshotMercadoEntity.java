package br.prospero.fo.mercado.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "snapshot_mercado")
public class SnapshotMercadoEntity {
    @EmbeddedId
    public SnapshotMercadoId id;
    @Column(nullable = false)
    public long revisao;
    @Column(nullable = false)
    public LocalDate sessao;
    @Column(name = "preparado_em", nullable = false)
    public Instant preparadoEm;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    public String conteudo;
}
