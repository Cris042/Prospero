package br.prospero.bo.posicoes.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "bo_log")
public class LogPosicoesEntity {
    @Id public int id;
    @Column(name = "topico_id", nullable = false, length = 22) public String topicoId;
}
