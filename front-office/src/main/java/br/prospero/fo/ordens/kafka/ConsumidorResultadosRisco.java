package br.prospero.fo.ordens.kafka;

import br.prospero.fo.comum.dominio.MensagemInvalidaException;
import br.prospero.fo.comum.kafka.ConfiguracaoKafka;
import br.prospero.fo.comum.kafka.ConsumoKafka;
import br.prospero.fo.comum.kafka.Rastreio;
import br.prospero.fo.ordens.actor.AplicarCancelamentoActor;
import br.prospero.fo.ordens.actor.AplicarRiscoActor;
import br.prospero.fo.ordens.dominio.ResultadoCancelamento;
import br.prospero.fo.ordens.dominio.ResultadoRisco;
import br.prospero.fo.ordens.helper.EnvelopeRisco;
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
import br.prospero.fo.comum.dominio.EventosRejeitados;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Adaptador Kafka: lê risco.resultados.v1 e avança a SAGA, sem passar pelo REST. */
@ApplicationScoped
public class ConsumidorResultadosRisco {
    private final AplicarRiscoActor actor;
    private final AplicarCancelamentoActor cancelamento;
    private final EventosRejeitados rejeitados;
    private final ConfiguracaoKafka configuracao;
    private final OpenTelemetry telemetria;
    private final Tracer tracer;
    private final MeterRegistry metricas;
    private final boolean habilitada;
    private ConsumoKafka consumo;
    private Thread linha;

    @Inject
    public ConsumidorResultadosRisco(AplicarRiscoActor actor, AplicarCancelamentoActor cancelamento,
                                     EventosRejeitados rejeitados,
                                     ConfiguracaoKafka configuracao, OpenTelemetry telemetria, Tracer tracer, MeterRegistry metricas,
                                     @ConfigProperty(name = "prospero.mensageria.habilitada", defaultValue = "true")
                                     boolean habilitada) {
        this.actor = actor;
        this.cancelamento = cancelamento;
        this.rejeitados = rejeitados;
        this.configuracao = configuracao;
        this.telemetria = telemetria;
        this.tracer = tracer;
        this.metricas = metricas;
        this.habilitada = habilitada;
    }

    void iniciar(@Observes StartupEvent evento) {
        if (!habilitada) {
            return;
        }
        consumo = new ConsumoKafka(() -> new KafkaConsumer<>(configuracao.consumidor("front-office-risco")), EnvelopeRisco.TOPICO_RESULTADOS,
                this::processar, rejeitados::registrar, metricas);
        linha = Thread.ofPlatform().name("consumidor-resultados-risco").daemon().start(consumo);
    }

    void parar(@Observes ShutdownEvent evento) throws InterruptedException {
        if (consumo != null) {
            consumo.parar();
            linha.join(5000);
        }
    }

    private static void exigirChave(String conta, String chave) {
        if (!conta.equals(chave)) {
            throw new MensagemInvalidaException("chave");
        }
    }

    void processar(ConsumerRecord<String, byte[]> registro) {
        Span span = tracer.spanBuilder("risco.resultado")
                .setParent(Rastreio.extrair(telemetria.getPropagators().getTextMapPropagator(), registro.headers()))
                .setSpanKind(SpanKind.CONSUMER).startSpan();
        try (Scope ignorado = span.makeCurrent()) {
            switch (EnvelopeRisco.ler(registro.value())) {
                case ResultadoRisco risco -> {
                    exigirChave(risco.conta(), registro.key());
                    actor.aplicar(risco);
                }
                case ResultadoCancelamento resultado -> {
                    exigirChave(resultado.conta(), registro.key());
                    cancelamento.aplicar(resultado);
                }
            }
        } finally {
            span.end();
        }
    }
}
