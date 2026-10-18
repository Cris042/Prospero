package br.prospero.fo.ordens.kafka;

import br.prospero.fo.comum.dominio.EventosRejeitados;
import br.prospero.fo.comum.dominio.MensagemInvalidaException;
import br.prospero.fo.comum.kafka.ConfiguracaoKafka;
import br.prospero.fo.comum.kafka.Rastreio;
import br.prospero.fo.ordens.actor.AplicarMatchingActor;
import br.prospero.fo.ordens.dominio.MatchingIncoerenteException;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Consumidor financeiro: erro de banco repete; inconsistência bloqueia sem confirmar o offset. */
@Readiness
@ApplicationScoped
public class ConsumidorMatching implements HealthCheck {
    private static final Logger LOG = Logger.getLogger(ConsumidorMatching.class);
    private final AplicarMatchingActor actor;
    private final EventosRejeitados rejeitados;
    private final ConfiguracaoKafka configuracao;
    private final OpenTelemetry telemetria;
    private final Tracer tracer;
    private final MeterRegistry metricas;
    private final boolean habilitada;
    private final AtomicBoolean pronto = new AtomicBoolean();
    private volatile boolean ativo;
    private volatile KafkaConsumer<String, byte[]> consumidor;
    private Thread linha;

    @Inject
    public ConsumidorMatching(AplicarMatchingActor actor, EventosRejeitados rejeitados,
                               ConfiguracaoKafka configuracao, OpenTelemetry telemetria, Tracer tracer,
                               MeterRegistry metricas,
                               @ConfigProperty(name = "prospero.mensageria.habilitada", defaultValue = "true") boolean habilitada) {
        this.actor = actor;
        this.rejeitados = rejeitados;
        this.configuracao = configuracao;
        this.telemetria = telemetria;
        this.tracer = tracer;
        this.metricas = metricas;
        this.habilitada = habilitada;
    }

    void iniciar(@Observes StartupEvent evento) {
        if (habilitada) {
            ativo = true;
            linha = Thread.ofPlatform().name("front-office-matching").daemon().start(this::consumir);
        }
    }

    void parar(@Observes ShutdownEvent evento) throws InterruptedException {
        ativo = false;
        pronto.set(false);
        if (consumidor != null) {
            consumidor.wakeup();
        }
        if (linha != null) {
            linha.join(5000);
        }
    }

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("matching-projecao").status(!habilitada || pronto.get()).build();
    }

    private void consumir() {
        var propriedades = configuracao.consumidor("front-office-matching");
        propriedades.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
        try (KafkaConsumer<String, byte[]> c = new KafkaConsumer<>(propriedades)) {
            consumidor = c;
            c.subscribe(List.of(EnvelopeMatching.TOPICO));
            while (ativo) {
                var registros = c.poll(Duration.ofMillis(500));
                pronto.set(!c.assignment().isEmpty());
                for (ConsumerRecord<String, byte[]> registro : registros) {
                    var particao = new TopicPartition(registro.topic(), registro.partition());
                    try {
                        processar(registro);
                        c.commitSync(Map.of(particao, new OffsetAndMetadata(registro.offset() + 1)));
                    } catch (MensagemInvalidaException | MatchingIncoerenteException invalido) {
                        pronto.set(false);
                        metricas.counter("prospero.matching.quarentena", "servico", "front-office").increment();
                        // Diagnóstico em transação própria, sem payload; o consumidor encerra mesmo se o banco falhar.
                        rejeitados.registrar(registro.topic(), registro.partition(), registro.offset(), invalido.getMessage());
                        LOG.error("Consumo matching bloqueado por inconsistência; offset não confirmado.");
                        return;
                    } catch (RuntimeException erro) {
                        pronto.set(false);
                        metricas.counter("prospero.matching.falhas", "servico", "front-office").increment();
                        if (c.assignment().contains(particao)) {
                            c.seek(particao, registro.offset());
                        }
                        LOG.warnf("Matching repetirá após falha transitória: %s", erro.getClass().getSimpleName());
                        Thread.sleep(500);
                    }
                }
            }
        } catch (WakeupException parada) {
            LOG.debug("Consumo matching encerrado.");
        } catch (InterruptedException parada) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException erro) {
            LOG.errorf("Consumo matching interrompido: %s", erro.getClass().getSimpleName());
        } finally {
            pronto.set(false);
            consumidor = null;
        }
    }

    void processar(ConsumerRecord<String, byte[]> registro) {
        Span span = tracer.spanBuilder("matching.projetar")
                .setParent(Rastreio.extrair(telemetria.getPropagators().getTextMapPropagator(), registro.headers()))
                .setSpanKind(SpanKind.CONSUMER).startSpan();
        try (Scope ignorado = span.makeCurrent()) {
            actor.aplicar(EnvelopeMatching.evento(registro.value(), registro.key()));
        } finally {
            span.end();
        }
    }
}
