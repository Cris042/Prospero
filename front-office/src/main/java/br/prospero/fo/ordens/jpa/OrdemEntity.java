package br.prospero.fo.ordens.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "ordem")
public class OrdemEntity {
    @Id
    public UUID id;
    @Column(nullable = false, length = 16)
    public String conta;
    @Column(nullable = false, length = 8)
    public String ticker;
    @Column(nullable = false, length = 6)
    public String lado;
    @Column(nullable = false, length = 10)
    public String tipo;
    @Column(nullable = false, length = 3)
    public String validade;
    @Column(nullable = false)
    public long quantidade;
    @Column(name = "preco_limite", precision = 12, scale = 2)
    public BigDecimal precoLimite;
    @Column(name = "preco_gatilho", precision = 12, scale = 2)
    public BigDecimal precoGatilho;
    @Column(name = "grupo_oco")
    public UUID grupoOco;
    @Column(name = "papel_oco", length = 11)
    public String papelOco;
    @Column(name = "grupo_envio", nullable = false)
    public UUID grupoEnvio;
    @Column(nullable = false, length = 16)
    public String estado;
    @Column(name = "data_sessao", nullable = false)
    public LocalDate dataSessao;
    @Column(name = "recebida_em", nullable = false)
    public Instant recebidaEm;
    @Version
    public long versao;
}
