package br.prospero.mo.sessao.jpa;

import br.prospero.mo.mensageria.dominio.MensagemSaida;
import br.prospero.mo.sessao.dominio.RepositorioSessao;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.query.NativeQuery;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Repetição concorrente do mesmo encerramento grava uma linha só (PK = id determinístico). */
@ApplicationScoped
public class RepositorioSessaoJpa implements RepositorioSessao {
    @Inject EntityManager em;

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean registrar(MensagemSaida m) {
        return em.createNativeQuery("""
                        INSERT INTO outbox (id, topico, chave, tipo, payload, traceparent, criado_em)
                        VALUES (:id, :topico, :chave, :tipo, :payload, :trace, :criado) ON CONFLICT (id) DO NOTHING""")
                .unwrap(NativeQuery.class)
                .setParameter("id", m.id()).setParameter("topico", m.topico()).setParameter("chave", m.chave())
                .setParameter("tipo", m.tipo()).setParameter("payload", m.payload())
                .setParameter("trace", m.traceparent(), String.class)
                .setParameter("criado", OffsetDateTime.ofInstant(m.criadaEm(), ZoneOffset.UTC)).executeUpdate() == 1;
    }
}
