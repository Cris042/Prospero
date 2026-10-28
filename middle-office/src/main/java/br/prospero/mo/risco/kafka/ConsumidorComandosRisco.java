package br.prospero.mo.risco.kafka;

import br.prospero.mo.cancelamento.actor.SolicitarCancelamentoActor;
import br.prospero.mo.mensageria.dominio.ParticaoBloqueadaException;
import br.prospero.mo.mensageria.kafka.ConfiguracaoKafka;
import br.prospero.mo.mensageria.kafka.ConsumoKafka;
import br.prospero.mo.mensageria.kafka.Rastreio;
import br.prospero.mo.risco.actor.AvaliarRiscoActor;
import br.prospero.mo.risco.dominio.ComandoRisco;
import br.prospero.mo.risco.helper.EnvelopeInvalidoException;
import br.prospero.mo.risco.helper.EnvelopeRisco;
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
import br.prospero.mo.mensageria.dominio.EventosRejeitados;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Adaptador Kafka: lê risco.comandos.v1 e chama o caso de uso, sem passar pelo REST. */
@ApplicationScoped
public class ConsumidorComandosRisco {
    private final AvaliarRiscoActor actor;
    private final SolicitarCancelamentoActor cancelamentos;
    private final EventosRejeitados rejeitados;
    private final ConfiguracaoKafka configuracao;
    private final OpenTelemetry telemetria;
    private final Tracer tracer;
    private final MeterRegistry metricas;
    private final boolean habilitada;
    private ConsumoKafka consumo;
    private Thread linha;

    @Inject
    public ConsumidorComandosRisco(AvaliarRiscoActor actor, SolicitarCancelamentoActor cancelamentos, EventosRejeitados rejeitados,
                                   ConfiguracaoKafka configuracao, OpenTelemetry telemetria, MeterRegistry metricas,
                                   @ConfigProperty(name = "prospero.mensageria.habilitada", defaultValue = "true")
                                   boolean habilitada) {
        this.actor = actor;
        this.cancelamentos = cancelamentos;
        this.rejeitados = rejeitados;
        this.configuracao = configuracao;
        this.telemetria = telemetria;
        this.tracer = telemetria.getTracer("br.prospero.mo.risco");
        this.metricas = metricas;
        this.habilitada = habilitada;
    }

    void iniciar(@Observes StartupEvent evento) {
        if (!habilitada) {
            return;
        }
        consumo = new ConsumoKafka(() -> new KafkaConsumer<>(configuracao.consumidor("middle-office-risco")), EnvelopeRisco.TOPICO_COMANDOS,
                this::processar, rejeitados::registrar, metricas);
        linha = Thread.ofPlatform().name("consumidor-risco").daemon().start(consumo);
    }

    void parar(@Observes ShutdownEvent evento) throws InterruptedException {
        if (consumo != null) {
            consumo.parar();
            linha.join(5000);
        }
    }

    void processar(ConsumerRecord<String, byte[]> registro) {
        Span span = tracer.spanBuilder("risco.avaliar")
                .setParent(Rastreio.extrair(telemetria.getPropagators().getTextMapPropagator(), registro.headers()))
                .setSpanKind(SpanKind.CONSUMER).startSpan();
        try (Scope ignorado = span.makeCurrent()) {
            var pedido = EnvelopeRisco.solicitacao(registro.value());
            if (pedido.isPresent()) {
                // Chave divergente é produtor fora do contrato; perder o pedido seria silencioso, então bloqueia.
                if (!pedido.get().conta().equals(registro.key())) {
                    throw new ParticaoBloqueadaException("CANCELAMENTO_CHAVE_DIVERGENTE");
                }
                cancelamentos.solicitar(pedido.get(), Rastreio.traceparent(span.getSpanContext()));
                return;
            }
            ComandoRisco comando = EnvelopeRisco.comando(registro.value());
            // A chave de partição é a conta: divergência indica produtor fora do contrato.
            if (!comando.conta().equals(registro.key())) {
                throw new EnvelopeInvalidoException("chave");
            }
            actor.avaliar(comando, Rastreio.traceparent(span.getSpanContext()));
        } finally {
            span.end();
        }
    }
}
