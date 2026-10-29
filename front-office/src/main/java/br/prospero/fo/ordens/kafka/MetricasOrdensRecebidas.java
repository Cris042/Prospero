package br.prospero.fo.ordens.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ordens ainda em RECEBIDA e idade da mais antiga, a cada 5 s (ADR-019). Sintoma de resultado de risco que não
 * chegou: quarentena, consumidor parado ou tópico perdido. Gauge conserva o último valor se a leitura falhar.
 */
@ApplicationScoped
public class MetricasOrdensRecebidas {
    private static final Logger LOG = Logger.getLogger(MetricasOrdensRecebidas.class);
    private final Instance<EntityManager> ems;
    private final MeterRegistry metricas;
    private final boolean habilitada;
    private final AtomicLong recebidas = new AtomicLong();
    private final AtomicLong idade = new AtomicLong();
    private ScheduledExecutorService agenda;

    @Inject
    public MetricasOrdensRecebidas(Instance<EntityManager> ems, MeterRegistry metricas,
                                   @ConfigProperty(name = "prospero.mensageria.habilitada", defaultValue = "true") boolean habilitada) {
        this.ems = ems;
        this.metricas = metricas;
        this.habilitada = habilitada;
    }

    void iniciar(@Observes StartupEvent evento) {
        if (!habilitada) return;
        metricas.gauge("prospero.ordens.recebidas", recebidas);
        metricas.gauge("prospero.ordens.recebidas.idade.segundos", idade);
        EntityManager em = ems.get();
        agenda = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("metricas-recebidas").daemon().factory());
        agenda.scheduleWithFixedDelay(() -> medir(em), 5, 5, TimeUnit.SECONDS);
    }

    void parar(@Observes ShutdownEvent evento) {
        if (agenda != null) agenda.shutdownNow();
    }

    private void medir(EntityManager em) {
        try {
            Object[] linha = QuarkusTransaction.requiringNew().call(() -> (Object[]) em.createNativeQuery("""
                    SELECT count(*), COALESCE(EXTRACT(EPOCH FROM now() - min(recebida_em)), 0)
                    FROM ordem WHERE estado = 'RECEBIDA'""").getSingleResult());
            recebidas.set(((Number) linha[0]).longValue());
            idade.set(Math.round(((Number) linha[1]).doubleValue()));
        } catch (RuntimeException erro) {
            LOG.debugf("Gauge de ordens RECEBIDA não atualizado: %s", erro.getClass().getSimpleName());
        }
    }
}
