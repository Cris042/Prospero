package br.prospero.mo.mensageria.kafka;

import br.prospero.mo.mensageria.dominio.MensagemQuarentena;
import br.prospero.mo.mensageria.dominio.Reaplicacao;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reaplicação da quarentena (ADR-019): cada consumidor registra seu processador por tópico, e o registro original
 * (tópico, partição, offset, chave, traceparent e payload) é reconstruído. A deduplicação de cada consumidor torna
 * a repetição inócua.
 */
@ApplicationScoped
public class ReaplicacaoKafka implements Reaplicacao {
    private final Map<String, ConsumoKafka.Processador> processadores = new ConcurrentHashMap<>();

    public void registrar(String topico, ConsumoKafka.Processador processador) {
        processadores.put(topico, processador);
    }

    @Override
    public boolean suporta(String topico) { return processadores.containsKey(topico); }

    @Override
    public void reaplicar(MensagemQuarentena m) {
        var processador = Optional.ofNullable(processadores.get(m.topico()))
                .orElseThrow(() -> new IllegalStateException("Tópico sem reprocessador."));
        var cabecalhos = new RecordHeaders();
        if (m.traceparent() != null) cabecalhos.add("traceparent", m.traceparent().getBytes(StandardCharsets.UTF_8));
        processador.processar(new ConsumerRecord<>(m.topico(), m.particao(), m.deslocamento(), ConsumerRecord.NO_TIMESTAMP,
                TimestampType.NO_TIMESTAMP_TYPE, ConsumerRecord.NULL_SIZE, ConsumerRecord.NULL_SIZE, m.chave(), m.payload(),
                cabecalhos, Optional.empty()));
    }
}
