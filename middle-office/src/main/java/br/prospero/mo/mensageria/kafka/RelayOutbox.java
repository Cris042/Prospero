package br.prospero.mo.mensageria.kafka;

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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Um publicador por serviço; urgência após commit e fallback compartilham o executor (ADR-018). */
@ApplicationScoped
public class RelayOutbox {
    private final Instance<EntityManager> ems;
    private EntityManager em;
    private final ConfiguracaoKafka configuracao;
    private final MeterRegistry metricas;
    private final boolean habilitada;
    private final AtomicLong pendentes = new AtomicLong();
    private KafkaProducer<String, byte[]> produtor;
    private volatile AgendadorOutbox agendador;
    @ConfigProperty(name = "prospero.outbox.urgencia.habilitada", defaultValue = "true")
    boolean urgencia;

    @Inject
    public RelayOutbox(Instance<EntityManager> ems, ConfiguracaoKafka configuracao, MeterRegistry metricas,
                       @ConfigProperty(name = "prospero.mensageria.habilitada", defaultValue = "true") boolean habilitada) {
        this.ems = ems;
        this.configuracao = configuracao;
        this.metricas = metricas;
        this.habilitada = habilitada;
    }

    void iniciar(@Observes StartupEvent evento) {
        if (!habilitada) return;
        em = ems.get();
        produtor = new KafkaProducer<>(configuracao.produtor());
        metricas.gauge("prospero.outbox.pendentes", pendentes);
        PublicadorOutbox publicador = new PublicadorOutbox(em, produtor, metricas);
        var agenda = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("relay-outbox").daemon().factory());
        var novo = new AgendadorOutbox(agenda, System::nanoTime,
                () -> QuarkusTransaction.requiringNew().call(publicador::publicarLote),
                () -> QuarkusTransaction.requiringNew().run(this::manter));
        agendador = novo;
        novo.iniciar();
    }

    /** Callback pós-commit não lê a mensagem nem publica na thread da transação financeira. */
    void notificar() {
        AgendadorOutbox atual = agendador;
        if (urgencia && atual != null) atual.solicitar();
    }

    void parar(@Observes ShutdownEvent evento) {
        AgendadorOutbox atual = agendador;
        agendador = null;
        if (atual != null) atual.close();
        if (produtor != null) produtor.close(Duration.ofSeconds(2));
    }

    /** Gauge e expurgo limitados, aproximadamente a cada cinco segundos, inclusive entre lotes. */
    private void manter() {
        pendentes.set(((Number) em.createNativeQuery("SELECT count(*) FROM outbox WHERE publicado_em IS NULL")
                .getSingleResult()).longValue());
        em.createNativeQuery("""
                DELETE FROM outbox WHERE id IN (SELECT id FROM outbox
                WHERE publicado_em < now() - INTERVAL '1 day' LIMIT 500)""").executeUpdate();
        em.createNativeQuery("""
                DELETE FROM evento_rejeitado WHERE id IN (SELECT id FROM evento_rejeitado
                WHERE recebido_em < now() - INTERVAL '7 days' LIMIT 500)""").executeUpdate();
    }
}
