package br.prospero.mo.mensageria.kafka;

import br.prospero.mo.mensageria.jpa.PublicacoesAdmissaoJpa;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jboss.logging.Logger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Publicação em transação própria: ack primeiro, marcação depois; reentrega continua possível. */
public final class PublicadorOutbox {
    private static final Logger LOG = Logger.getLogger(PublicadorOutbox.class);
    private static final int LOTE = 50;
    private static final Duration LOTE_MAXIMO = Duration.ofSeconds(20);
    private final EntityManager em;
    private final Producer<String, byte[]> produtor;
    private final MeterRegistry metricas;

    public PublicadorOutbox(EntityManager em, Producer<String, byte[]> produtor, MeterRegistry metricas) {
        this.em = em;
        this.produtor = produtor;
        this.metricas = metricas;
    }

    /** FALHOU também pausa lote interrompido/esgotado no tempo; não é rejeição financeira. */
    public AgendadorOutbox.Resultado publicarLote() {
        List<?> linhas = em.createNativeQuery("""
                        SELECT id, topico, chave, payload, traceparent, tipo FROM outbox WHERE publicado_em IS NULL
                        ORDER BY criado_em, id LIMIT :n FOR UPDATE SKIP LOCKED""")
                .setParameter("n", LOTE).getResultList();
        List<UUID> publicados = new ArrayList<>();
        List<PublicacoesAdmissaoJpa.Confirmada> confirmadas = new ArrayList<>();
        long limite = System.nanoTime() + LOTE_MAXIMO.toNanos();
        boolean interrompido = false;
        for (Object linha : linhas) {
            if (System.nanoTime() - limite > 0) {
                interrompido = true;
                break;
            }
            Object[] c = (Object[]) linha;
            var mensagem = new PublicacoesAdmissaoJpa.Confirmada((UUID) c[0], (String) c[1], (String) c[2],
                    (String) c[5], (String) c[3], (String) c[4]);
            ProducerRecord<String, byte[]> registro = new ProducerRecord<>((String) c[1], (String) c[2],
                    ((String) c[3]).getBytes(StandardCharsets.UTF_8));
            if (c[4] != null) registro.headers().add("traceparent", ((String) c[4]).getBytes(StandardCharsets.UTF_8));
            try {
                produtor.send(registro).get(10, TimeUnit.SECONDS);
                publicados.add((UUID) c[0]);
                confirmadas.add(mensagem);
            } catch (InterruptedException erro) {
                Thread.currentThread().interrupt();
                interrompido = true;
                break;
            } catch (Exception erro) {
                em.createNativeQuery("UPDATE outbox SET tentativas = tentativas + 1 WHERE id = :id")
                        .setParameter("id", c[0]).executeUpdate();
                LOG.warnf("Publicacao da outbox falhou: id=%s erro=%s", c[0], erro.getClass().getSimpleName());
                interrompido = true;
                break;
            }
        }
        if (!publicados.isEmpty()) {
            em.createNativeQuery("UPDATE outbox SET publicado_em = now() WHERE id IN (:ids)")
                    .setParameter("ids", publicados).executeUpdate();
            new PublicacoesAdmissaoJpa(em).confirmar(confirmadas);
            metricas.counter("prospero.outbox.publicados").increment(publicados.size());
        }
        if (interrompido) return AgendadorOutbox.Resultado.FALHOU;
        return publicados.size() == LOTE ? AgendadorOutbox.Resultado.CONTINUAR : AgendadorOutbox.Resultado.ESGOTADO;
    }
}
