package br.prospero.me.matching.kafka;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.Map;

/** Escopo de consumo independente da disponibilidade do exportador; não registra payload. */
public final class RastreioMatching {
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
        public String get(Map<String, String> carrier, String key) { return carrier.get(key); }
    };
    private final Tracer tracer;
    private final TextMapPropagator propagador;

    public RastreioMatching(OpenTelemetry telemetry, Tracer tracer) {
        this.tracer = tracer;
        this.propagador = telemetry.getPropagators().getTextMapPropagator();
    }

    public <T> T executar(String traceparent, java.util.concurrent.Callable<T> trabalho) throws Exception {
        var carrier = traceparent == null ? Map.<String, String>of() : Map.of("traceparent", traceparent);
        var parent = propagador.extract(Context.root(), carrier, GETTER);
        var span = tracer.spanBuilder("matching.consumir").setSpanKind(SpanKind.CONSUMER).setParent(parent).startSpan();
        try (var escopo = span.makeCurrent()) {
            return trabalho.call();
        } catch (Exception erro) {
            span.setStatus(StatusCode.ERROR);
            throw erro;
        } finally {
            span.end();
        }
    }

    public static String atual() {
        var contexto = Span.current().getSpanContext();
        return contexto.isValid() ? "00-" + contexto.getTraceId() + "-" + contexto.getSpanId()
                + "-" + contexto.getTraceFlags().asHex() : null;
    }
}
