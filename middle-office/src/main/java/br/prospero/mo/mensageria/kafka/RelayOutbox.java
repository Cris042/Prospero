package br.prospero.mo.mensageria.kafka;

import br.prospero.mo.mensageria.dominio.PromotorCancelamento;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
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
import java.util.ArrayList;
import java.util.List;
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
    private final PromotorCancelamento promotor;
    private final AtomicLong pendentes = new AtomicLong();
    private final AtomicLong idadeQuarentena = new AtomicLong();
    private MultiGauge quarentena;
    private KafkaProducer<String, byte[]> produtor;
    private volatile AgendadorOutbox agendador;
    @ConfigProperty(name = "prospero.outbox.urgencia.habilitada", defaultValue = "true")
    boolean urgencia;

    @Inject
    public RelayOutbox(Instance<EntityManager> ems, ConfiguracaoKafka configuracao, MeterRegistry metricas,
                       PromotorCancelamento promotor,
                       @ConfigProperty(name = "prospero.mensageria.habilitada", defaultValue = "true") boolean habilitada) {
        this.ems = ems;
        this.configuracao = configuracao;
        this.metricas = metricas;
        this.promotor = promotor;
        this.habilitada = habilitada;
    }

    void iniciar(@Observes StartupEvent evento) {
        if (!habilitada) return;
        em = ems.get();
        produtor = new KafkaProducer<>(configuracao.produtor());
        metricas.gauge("prospero.outbox.pendentes", pendentes);
        quarentena = MultiGauge.builder("prospero.quarentena.pendentes")
                .description("Mensagens em quarentena aguardando reprocessamento ou descarte").register(metricas);
        metricas.gauge("prospero.quarentena.idade.segundos", idadeQuarentena);
        PublicadorOutbox publicador = new PublicadorOutbox(em, produtor, metricas, promotor);
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
        // Retenção da quarentena (ADR-019): 30 dias depois de resolvida. Pendente nunca é apagada: é a única cópia do
        // payload quando o tópico de origem já expirou, e o alerta continua ativo até alguém decidir.
        em.createNativeQuery("""
                DELETE FROM mensagem_quarentena WHERE id IN (SELECT id FROM mensagem_quarentena
                WHERE resolvido_em < now() - INTERVAL '30 days' LIMIT 500)""").executeUpdate();
        medirQuarentena();
    }

    /** Pendentes por tópico e motivo e idade da mais antiga; linhas somem do gauge quando a quarentena esvazia. */
    private void medirQuarentena() {
        List<?> linhas = em.createNativeQuery("""
                SELECT topico, motivo, count(*), EXTRACT(EPOCH FROM now() - min(quarentenado_em))
                FROM mensagem_quarentena WHERE resolvido_em IS NULL GROUP BY topico, motivo""").getResultList();
        List<MultiGauge.Row<?>> pendentesPorMotivo = new ArrayList<>();
        double idade = 0;
        for (Object linha : linhas) {
            Object[] l = (Object[]) linha;
            pendentesPorMotivo.add(MultiGauge.Row.of(Tags.of("topico", (String) l[0], "motivo", (String) l[1]),
                    ((Number) l[2]).doubleValue()));
            idade = Math.max(idade, ((Number) l[3]).doubleValue());
        }
        quarentena.register(pendentesPorMotivo, true);
        idadeQuarentena.set(Math.round(idade));
    }
}
