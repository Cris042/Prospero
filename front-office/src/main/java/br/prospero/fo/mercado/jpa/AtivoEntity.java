package br.prospero.fo.mercado.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "ativo")
public class AtivoEntity {
    @Id
    @Column(length = 8)
    public String ticker;
    @Column(nullable = false, length = 80)
    public String nome;
    @Column(name = "preco_referencia", nullable = false, precision = 12, scale = 2)
    public BigDecimal precoReferencia;
    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal tick;
    @Column(nullable = false)
    public long lote;
    @Column(nullable = false)
    public short ordem;
}
