package br.prospero.mo.matching.kafka;

import br.prospero.mo.matching.actor.AplicarMatchingActor;
import br.prospero.mo.matching.helper.EnvelopeMatching;
import br.prospero.mo.mensageria.dominio.EventosRejeitados;
import br.prospero.mo.mensageria.kafka.ConfiguracaoKafka;
import br.prospero.mo.mensageria.kafka.ConsumoKafka;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/** Falhas semânticas bloqueiam o consumo; falhas transitórias repetem sem descarte financeiro. */
@ApplicationScoped
@Readiness
public class ConsumidorMatching implements HealthCheck {
    @Inject AplicarMatchingActor actor;
    @Inject EventosRejeitados rejeitados;
    @Inject ConfiguracaoKafka configuracao;
    @Inject MeterRegistry metricas;
    @Inject io.opentelemetry.api.OpenTelemetry telemetry;
    @Inject io.opentelemetry.api.trace.Tracer tracer;
    @ConfigProperty(name="prospero.mensageria.habilitada",defaultValue="true") boolean habilitada;
    private ConsumoKafka consumo;
    private Thread linha;

    void iniciar(@Observes StartupEvent evento) {
        if (!habilitada) return;
        consumo=new ConsumoKafka(() -> new KafkaConsumer<>(configuracao.consumidor("middle-office-matching")),
                "matching.eventos.v1", r -> {
                    var pai = br.prospero.mo.mensageria.kafka.Rastreio.extrair(
                            telemetry.getPropagators().getTextMapPropagator(), r.headers());
                    var span = tracer.spanBuilder("matching.projetar").setParent(pai)
                            .setSpanKind(io.opentelemetry.api.trace.SpanKind.CONSUMER).startSpan();
                    try (var escopo = span.makeCurrent()) {
                        var e=EnvelopeMatching.ler(r.value());
                        EnvelopeMatching.exigir(EnvelopeMatching.texto(e,"ticker").equals(r.key()),"chave");
                        actor.aplicar(e);
                    } catch (RuntimeException erro) {
                        span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
                        throw erro;
                    } finally {
                        span.end();
                    }
                },rejeitados::registrar,metricas,true);
        linha=Thread.ofPlatform().name("consumidor-matching-mo").daemon().start(consumo);
    }
    void parar(@Observes ShutdownEvent evento) throws InterruptedException {
        if(consumo!=null) { consumo.parar(); linha.join(5000); }
    }
    @Override public HealthCheckResponse call() {
        return HealthCheckResponse.named("matching-financeiro").status(!habilitada || consumo!=null && consumo.saudavel()).build();
    }
}
