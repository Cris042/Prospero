package br.prospero.fo.ordens.kafka;

import br.prospero.fo.comum.dominio.EventosRejeitados;
import br.prospero.fo.comum.dominio.MensagemInvalidaException;
import br.prospero.fo.comum.kafka.ConfiguracaoKafka;
import br.prospero.fo.comum.kafka.Rastreio;
import br.prospero.fo.ordens.actor.AplicarContabilizacaoActor;
import br.prospero.fo.ordens.dominio.MatchingIncoerenteException;
import br.prospero.fo.ordens.helper.EnvelopeContabilizacao;
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
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
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
public class ConsumidorContabilizacao implements HealthCheck {
    private static final Logger LOG = Logger.getLogger(ConsumidorContabilizacao.class);
    private final AplicarContabilizacaoActor actor;
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
    public ConsumidorContabilizacao(AplicarContabilizacaoActor actor, EventosRejeitados rejeitados,
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
            linha = Thread.ofPlatform().name("front-office-contabilizacao").daemon().start(this::consumir);
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
        return HealthCheckResponse.named("contabilizacao-projecao").status(!habilitada || pronto.get()).build();
    }

    private void consumir() {
        var propriedades = configuracao.consumidor("front-office-contabilizacao");
        propriedades.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
        try (KafkaConsumer<String, byte[]> c = new KafkaConsumer<>(propriedades)) {
            consumidor = c;
            c.subscribe(List.of(EnvelopeContabilizacao.TOPICO));
            while (ativo) {
                var registros = lerLote(c);
                for (ConsumerRecord<String, byte[]> registro : registros) {
                    var particao = new TopicPartition(registro.topic(), registro.partition());
                    try {
                        processar(registro);
                        c.commitSync(Map.of(particao, new OffsetAndMetadata(registro.offset() + 1)));
                        pronto.set(true);
                    } catch (MensagemInvalidaException | MatchingIncoerenteException invalido) {
                        pronto.set(false);
                        metricas.counter("prospero.contabilizacao.quarentena", "servico", "front-office").increment();
                        // Quarentena em transação própria, com payload (ADR-019); o consumidor encerra mesmo se o banco falhar.
                        rejeitados.registrar(br.prospero.fo.comum.kafka.ConsumoKafka.quarentena(registro, invalido.getMessage(), 1));
                        LOG.error("Consumo contabilização bloqueado por inconsistência; offset não confirmado.");
                        return;
                    } catch (RuntimeException erro) {
                        pronto.set(false);
                        metricas.counter("prospero.contabilizacao.falhas", "servico", "front-office").increment();
                        if (c.assignment().contains(particao)) {
                            c.seek(particao, registro.offset());
                        }
                        LOG.warnf("Contabilização repetirá após falha transitória: %s", erro.getClass().getSimpleName());
                        Thread.sleep(500);
                    }
                }
            }
        } catch (WakeupException parada) {
            LOG.debug("Consumo contabilização encerrado.");
        } catch (InterruptedException parada) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException erro) {
            LOG.errorf("Consumo contabilização interrompido: %s", erro.getClass().getSimpleName());
        } finally {
            pronto.set(false);
            consumidor = null;
        }
    }

    ConsumerRecords<String, byte[]> lerLote(Consumer<String, byte[]> kafka) throws InterruptedException {
        try {
            var registros = kafka.poll(Duration.ofMillis(500));
            pronto.set(!kafka.assignment().isEmpty());
            return registros;
        } catch (WakeupException parada) {
            throw parada;
        } catch (RuntimeException erro) {
            pronto.set(false);
            metricas.counter("prospero.contabilizacao.falhas", "servico", "front-office").increment();
            LOG.warnf("Contabilização repetirá leitura após falha transitória: %s", erro.getClass().getSimpleName());
            Thread.sleep(500);
            return ConsumerRecords.empty();
        }
    }

    void processar(ConsumerRecord<String, byte[]> registro) {
        Span span = tracer.spanBuilder("contabilizacao.projetar")
                .setParent(Rastreio.extrair(telemetria.getPropagators().getTextMapPropagator(), registro.headers()))
                .setSpanKind(SpanKind.CONSUMER).startSpan();
        try (Scope ignorado = span.makeCurrent()) {
            actor.aplicar(EnvelopeContabilizacao.evento(registro.value(), registro.key()));
        } finally {
            span.end();
        }
    }
}
