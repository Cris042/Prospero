package br.prospero.fo.comum.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publica a outbox em ordem de criação (ADR-012): lote com FOR UPDATE SKIP LOCKED, envio síncrono com ack e
 * publicado_em marcado só depois. Falha interrompe o lote e repete no próximo ciclo (pelo menos uma vez).
 */
@ApplicationScoped
public class RelayOutbox {
    private static final Logger LOG = Logger.getLogger(RelayOutbox.class);
    private static final int LOTE = 50;
    private static final int CICLOS_MANUTENCAO = 25;
    private final Instance<EntityManager> ems;
    private EntityManager em;
    private final ConfiguracaoKafka configuracao;
    private final MeterRegistry metricas;
    private final boolean habilitada;
    private final AtomicLong pendentes = new AtomicLong();
    private KafkaProducer<String, byte[]> produtor;
    private ScheduledExecutorService agenda;
    private int ciclos;

    @Inject
    public RelayOutbox(Instance<EntityManager> ems, ConfiguracaoKafka configuracao, MeterRegistry metricas,
                       @ConfigProperty(name = "prospero.mensageria.habilitada", defaultValue = "true") boolean habilitada) {
        this.ems = ems;
        this.configuracao = configuracao;
        this.metricas = metricas;
        this.habilitada = habilitada;
    }

    void iniciar(@Observes StartupEvent evento) {
        if (!habilitada) {
            return;
        }
        // Resolvido só quando habilitado: no perfil de testes o EntityManager está inativo.
        em = ems.get();
        produtor = new KafkaProducer<>(configuracao.produtor());
        metricas.gauge("prospero.outbox.pendentes", pendentes);
        agenda = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("relay-outbox").daemon().factory());
        agenda.scheduleWithFixedDelay(this::ciclo, 1, 200, TimeUnit.MILLISECONDS);
    }

    void parar(@Observes ShutdownEvent evento) {
        if (agenda != null) {
            agenda.shutdownNow();
            produtor.close(Duration.ofSeconds(2));
        }
    }

    private void ciclo() {
        try {
            int publicados;
            do {
                publicados = QuarkusTransaction.requiringNew().call(this::publicarLote);
            } while (publicados == LOTE);
            if (++ciclos % CICLOS_MANUTENCAO == 0) {
                QuarkusTransaction.requiringNew().run(this::manter);
            }
        } catch (RuntimeException erro) {
            LOG.warnf("Relay da outbox falhou; nova tentativa no próximo ciclo: %s", erro.getClass().getSimpleName());
        }
    }

    /** Devolve quantas linhas publicou; menos que o lote encerra o ciclo. */
    private int publicarLote() {
        List<?> linhas = em.createNativeQuery("""
                        SELECT id, topico, chave, payload, traceparent FROM outbox WHERE publicado_em IS NULL
                        ORDER BY criado_em LIMIT :n FOR UPDATE SKIP LOCKED""").setParameter("n", LOTE).getResultList();
        List<UUID> publicados = new ArrayList<>();
        for (Object linha : linhas) {
            Object[] c = (Object[]) linha;
            ProducerRecord<String, byte[]> registro = new ProducerRecord<>((String) c[1], (String) c[2],
                    ((String) c[3]).getBytes(StandardCharsets.UTF_8));
            if (c[4] != null) {
                registro.headers().add("traceparent", ((String) c[4]).getBytes(StandardCharsets.UTF_8));
            }
            try {
                produtor.send(registro).get(10, TimeUnit.SECONDS);
                publicados.add((UUID) c[0]);
            } catch (InterruptedException erro) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception erro) {
                em.createNativeQuery("UPDATE outbox SET tentativas = tentativas + 1 WHERE id = :id")
                        .setParameter("id", c[0]).executeUpdate();
                LOG.warnf("Publicação da outbox falhou: id=%s erro=%s", c[0], erro.getClass().getSimpleName());
                break;
            }
        }
        if (!publicados.isEmpty()) {
            em.createNativeQuery("UPDATE outbox SET publicado_em = now() WHERE id IN (:ids)")
                    .setParameter("ids", publicados).executeUpdate();
            metricas.counter("prospero.outbox.publicados").increment(publicados.size());
        }
        return publicados.size() == linhas.size() ? publicados.size() : 0;
    }

    /** Atualiza o gauge de pendentes e expurga, em lotes, mensagens publicadas há mais de um dia. */
    private void manter() {
        pendentes.set(((Number) em.createNativeQuery("SELECT count(*) FROM outbox WHERE publicado_em IS NULL")
                .getSingleResult()).longValue());
        em.createNativeQuery("""
                DELETE FROM outbox WHERE id IN (SELECT id FROM outbox
                WHERE publicado_em < now() - INTERVAL '1 day' LIMIT 500)""").executeUpdate();
    }
}
