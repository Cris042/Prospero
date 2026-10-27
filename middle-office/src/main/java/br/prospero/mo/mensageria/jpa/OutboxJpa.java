package br.prospero.mo.mensageria.jpa;

import br.prospero.mo.mensageria.dominio.MensagemSaida;
import br.prospero.mo.mensageria.kafka.AvisoOutbox;
import br.prospero.mo.mensageria.dominio.Outbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.query.NativeQuery;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@ApplicationScoped
public class OutboxJpa implements Outbox {
    private final EntityManager em;
    @Inject AvisoOutbox avisoOutbox;

    @Inject
    public OutboxJpa(EntityManager em) {
        this.em = em;
    }

    /** MANDATORY: a mensagem só existe se o efeito que a originou confirmar na mesma transação. */
    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void gravar(MensagemSaida m) {
        em.createNativeQuery("""
                        INSERT INTO outbox (id, topico, chave, tipo, payload, traceparent, criado_em)
                        VALUES (:id, :topico, :chave, :tipo, :payload, :trace, :criado)""")
                .unwrap(NativeQuery.class)
                .setParameter("id", m.id()).setParameter("topico", m.topico()).setParameter("chave", m.chave())
                .setParameter("tipo", m.tipo()).setParameter("payload", m.payload())
                .setParameter("trace", m.traceparent(), String.class)
                .setParameter("criado", OffsetDateTime.ofInstant(m.criadaEm(), ZoneOffset.UTC)).executeUpdate();
        avisoOutbox.registrar();
    }
}
