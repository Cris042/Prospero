package br.prospero.fo.comum.kafka;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Propagação W3C pelo header traceparent do Kafka; a trace HTTP de origem continua no consumidor. */
public final class Rastreio {
    private static final TextMapGetter<Headers> HEADERS = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers headers) {
            List<String> chaves = new ArrayList<>();
            headers.forEach(h -> chaves.add(h.key()));
            return chaves;
        }

        @Override
        public String get(Headers headers, String chave) {
            Header header = headers == null ? null : headers.lastHeader(chave);
            return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        }
    };

    private Rastreio() {
    }

    public static Context extrair(TextMapPropagator propagador, Headers headers) {
        return propagador.extract(Context.root(), headers, HEADERS);
    }

    /** traceparent do span corrente, ou null quando a telemetria está desativada. */
    public static String traceparent(SpanContext contexto) {
        if (!contexto.isValid()) {
            return null;
        }
        return "00-" + contexto.getTraceId() + "-" + contexto.getSpanId() + "-" + contexto.getTraceFlags().asHex();
    }
}
