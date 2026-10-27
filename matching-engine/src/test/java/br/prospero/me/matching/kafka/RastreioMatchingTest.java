package br.prospero.me.matching.kafka;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RastreioMatchingTest {
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final String PAI = "0123456789abcdef";
    private static final String CONTEXTO = "00-" + TRACE + "-" + PAI + "-01";

    @Test
    void consumidorCriaSpanRealNoTraceRecebidoEFechaEscopoAoRetornar() throws Exception {
        var spans = new Coletor();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(spans).build()) {
            var otel = OpenTelemetrySdk.builder().setTracerProvider(provider)
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance())).build();
            var rastreio = new RastreioMatching(otel, otel.getTracer("matching-engine"));
            String resultado = rastreio.executar(CONTEXTO, () -> {
                assertEquals(TRACE, Span.current().getSpanContext().getTraceId());
                assertTrue(RastreioMatching.atual().startsWith("00-" + TRACE + "-"));
                return "efeito-confirmado";
            });
            assertEquals("efeito-confirmado", resultado);
            assertNull(RastreioMatching.atual());
            assertFalse(Span.current().getSpanContext().isValid());
            assertEquals(1, spans.terminados.size());
            var span = spans.terminados.getFirst();
            assertEquals(TRACE, span.getTraceId());
            assertEquals(PAI, span.getParentSpanId());
            assertNotEquals(PAI, span.getSpanId());
            assertEquals(SpanKind.CONSUMER, span.getKind());
            assertEquals("matching-engine", span.getInstrumentationScopeInfo().getName());
        }
    }

    @Test
    void erroPropagaEncerraSpanERestauraContextoSemRegistrarPayload() throws Exception {
        var spans = new Coletor();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(spans).build()) {
            var otel = OpenTelemetrySdk.builder().setTracerProvider(provider)
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance())).build();
            var rastreio = new RastreioMatching(otel, otel.getTracer("matching-engine"));
            var falha = new IllegalStateException("payload-privado-que-nao-pode-ir-para-trace");
            var propagada = assertThrows(IllegalStateException.class, () -> rastreio.executar(CONTEXTO, () -> { throw falha; }));
            assertSame(falha, propagada);
            assertFalse(Span.current().getSpanContext().isValid());
            assertEquals(1, spans.terminados.size());
            assertTrue(spans.terminados.getFirst().getEvents().isEmpty());
            assertFalse(spans.terminados.getFirst().getAttributes().toString().contains("payload-privado"));
        }
    }

    private static final class Coletor implements SpanProcessor {
        private final List<SpanData> terminados = new ArrayList<>();
        @Override public void onStart(Context pai, ReadWriteSpan span) { }
        @Override public boolean isStartRequired() { return false; }
        @Override public void onEnd(ReadableSpan span) { terminados.add(span.toSpanData()); }
        @Override public boolean isEndRequired() { return true; }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode forceFlush() { return CompletableResultCode.ofSuccess(); }
    }
}
