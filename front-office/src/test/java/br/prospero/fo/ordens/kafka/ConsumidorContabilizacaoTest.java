package br.prospero.fo.ordens.kafka;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConsumidorContabilizacaoTest {
    @Test
    void falhaTransitoriaNoPollNaoImpedeProximaLeituraNemConsomeRegistro() throws Exception {
        var metricas = new SimpleMeterRegistry();
        var consumidor = new ConsumidorContabilizacao(null, null, null, null, null, metricas, true);
        var particao = new TopicPartition("acompanhamento.eventos.v1", 0);
        try (var kafka = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST) {
            private boolean primeira = true;
            @Override
            public synchronized ConsumerRecords<String, byte[]> poll(Duration prazo) {
                if (primeira) { primeira = false; throw new KafkaException("falha sintética transitória"); }
                return super.poll(prazo);
            }
        }) {
            kafka.assign(List.of(particao));
            kafka.updateBeginningOffsets(Map.of(particao, 0L));
            kafka.addRecord(new ConsumerRecord<>(particao.topic(), 0, 0, "ALFA3", new byte[]{1}));
            assertTrue(consumidor.lerLote(kafka).isEmpty());
            assertEquals(HealthCheckResponse.Status.DOWN, consumidor.call().getStatus());
            assertEquals(1, metricas.get("prospero.contabilizacao.falhas").counter().count());
            var recuperado = consumidor.lerLote(kafka);
            assertEquals(1, recuperado.count());
            assertEquals(0, recuperado.iterator().next().offset());
        }
    }

    @Test
    void wakeupPropagaParaEncerrarConsumoSemRetentativa() {
        var consumidor = new ConsumidorContabilizacao(null, null, null, null, null, new SimpleMeterRegistry(), true);
        try (var kafka = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST)) {
            kafka.wakeup();
            assertThrows(WakeupException.class, () -> consumidor.lerLote(kafka));
        }
    }
}
