package br.prospero.bo.acompanhamento.jpa;

import br.prospero.bo.acompanhamento.helper.EventoContabilizacao;
import br.prospero.bo.posicoes.dominio.EventoMatching;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.query.NativeQuery;
import java.time.Instant;

/** Participa da transação de posição/razão/checkpoint; o relay publica após o commit. */
@ApplicationScoped
public class OutboxContabilizacaoJpa {
    private final Instance<EntityManager> gerenciadores;
    @Inject
    public OutboxContabilizacaoJpa(Instance<EntityManager> gerenciadores) { this.gerenciadores = gerenciadores; }

    public void registrar(EventoMatching evento) {
        if (!(evento.detalhe() instanceof EventoMatching.Negocio negocio)) throw new IllegalArgumentException("Negócio obrigatório");
        gerenciadores.get().createNativeQuery("""
                INSERT INTO bo_outbox(id,topico,chave,tipo,payload,traceparent)
                VALUES(:id,:topico,:chave,'NEGOCIO_CONTABILIZADO',:payload,:trace)
                """).unwrap(NativeQuery.class).setParameter("id", EventoContabilizacao.id(negocio.id()))
                .setParameter("topico", EventoContabilizacao.TOPICO).setParameter("chave", evento.ticker())
                .setParameter("payload", EventoContabilizacao.criar(evento, Instant.now()))
                .setParameter("trace", evento.traceparent(), String.class).executeUpdate();
    }
}
