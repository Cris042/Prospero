package br.prospero.fo.comum.jpa;

import br.prospero.fo.comum.dominio.EventosRejeitados;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Diagnóstico limitado de mensagens fora do contrato: posição no tópico e motivo, nunca o conteúdo. */
@ApplicationScoped
public class EventosRejeitadosJpa implements EventosRejeitados {
    private final EntityManager em;

    @Inject
    public EventosRejeitadosJpa(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void registrar(String topico, int particao, long deslocamento, String motivo) {
        em.createNativeQuery("""
                        INSERT INTO evento_rejeitado (topico, particao, deslocamento, motivo, recebido_em)
                        VALUES (:t, :p, :d, :m, :r)""")
                .setParameter("t", topico).setParameter("p", particao).setParameter("d", deslocamento)
                .setParameter("m", motivo.length() > 120 ? motivo.substring(0, 120) : motivo)
                .setParameter("r", OffsetDateTime.now(ZoneOffset.UTC)).executeUpdate();
    }
}
