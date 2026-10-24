package br.prospero.mo.liquidacao.kafka;

import br.prospero.mo.liquidacao.actor.AplicarLiquidacaoActor;
import br.prospero.mo.liquidacao.dominio.MotivoRecusa;
import br.prospero.mo.liquidacao.dominio.RegistroLiquidacao;
import br.prospero.mo.liquidacao.dominio.RepositorioLiquidacao;
import br.prospero.mo.liquidacao.dominio.ResumoLiquidacao;
import br.prospero.mo.mensageria.dominio.EventosRejeitados;
import br.prospero.mo.mensageria.kafka.ConfiguracaoKafka;
import br.prospero.mo.mensageria.kafka.ConsumoKafka;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Consumidor de liquidacao.eventos.v1 (ADR-016), grupo próprio, modo financeiro do ConsumoKafka: falha técnica repete
 * sem descarte; pendência e recusa são estados persistidos pelo actor e a partição avança. A prontidão reflete só o
 * consumidor vivo e sem falha técnica em curso, nunca o volume aguardando. Métricas lidas do banco a cada 5 s.
 */
@ApplicationScoped
@Readiness
public class ConsumidorLiquidacao implements HealthCheck {
    public static final String TOPICO = "liquidacao.eventos.v1";
    public static final String GRUPO = "middle-office-liquidacao";
    private static final Logger LOG = Logger.getLogger(ConsumidorLiquidacao.class);
    @Inject AplicarLiquidacaoActor actor;
    @Inject RepositorioLiquidacao repositorio;
    @Inject EventosRejeitados rejeitados;
    @Inject ConfiguracaoKafka configuracao;
    @Inject MeterRegistry metricas;
    @ConfigProperty(name = "prospero.mensageria.habilitada", defaultValue = "true") boolean habilitada;
    private final AtomicLong aguardando = new AtomicLong();
    private final AtomicLong idade = new AtomicLong();
    private final AtomicLong pendentes = new AtomicLong();
    private final Map<MotivoRecusa, AtomicLong> recusadas = new EnumMap<>(MotivoRecusa.class);
    private ConsumoKafka consumo;
    private Thread linha;
    private ScheduledExecutorService agenda;

    /** Processador do registro: separado para os testes com eventos sintéticos e MockConsumer. */
    public static ConsumoKafka consumo(Supplier<Consumer<String, byte[]>> fabrica, AplicarLiquidacaoActor actor,
                                       EventosRejeitados rejeitados, MeterRegistry metricas) {
        return new ConsumoKafka(fabrica, TOPICO, r -> actor.aplicar(new RegistroLiquidacao(r.key(), r.value(),
                r.partition(), r.offset())), rejeitados::registrar, metricas, true);
    }

    void iniciar(@Observes StartupEvent evento) {
        if (!habilitada) {
            return;
        }
        registrarMetricas();
        consumo = consumo(() -> new KafkaConsumer<>(configuracao.consumidor(GRUPO)), actor, rejeitados, metricas);
        linha = Thread.ofPlatform().name("consumidor-liquidacao-mo").daemon().start(consumo);
        agenda = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("metricas-liquidacao").daemon().factory());
        agenda.scheduleWithFixedDelay(this::atualizarMetricas, 0, 5, TimeUnit.SECONDS);
    }

    private void registrarMetricas() {
        metricas.gauge("prospero.liquidacao.aguardando", aguardando);
        metricas.gauge("prospero.liquidacao.aguardando.idade.segundos", idade);
        metricas.gauge("prospero.liquidacao.expectativas.pendentes", pendentes);
        for (MotivoRecusa motivo : MotivoRecusa.values()) {
            recusadas.put(motivo, metricas.gauge("prospero.liquidacao.recusadas",
                    List.of(Tag.of("motivo", motivo.name())), new AtomicLong()));
        }
    }

    private void atualizarMetricas() {
        try {
            ResumoLiquidacao resumo = repositorio.resumo();
            aguardando.set(resumo.aguardando());
            idade.set(resumo.idadeMaisAntigoSegundos());
            pendentes.set(resumo.pendentes());
            resumo.recusadas().forEach((motivo, total) -> recusadas.get(motivo).set(total));
        } catch (RuntimeException erro) {
            LOG.debugf("Métricas de liquidação não atualizadas: %s", erro.getClass().getSimpleName());
        }
    }

    void parar(@Observes ShutdownEvent evento) throws InterruptedException {
        if (agenda != null) {
            agenda.shutdownNow();
        }
        if (consumo != null) {
            consumo.parar();
            linha.join(5000);
        }
    }

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("liquidacao-financeira").status(!habilitada || consumo != null && consumo.saudavel())
                .build();
    }
}
