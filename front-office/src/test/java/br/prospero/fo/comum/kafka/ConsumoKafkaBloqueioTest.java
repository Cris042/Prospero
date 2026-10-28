package br.prospero.fo.comum.kafka;

import br.prospero.fo.comum.dominio.ParticaoBloqueadaException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** RF10c2a: tipo reconhecido sem leitor bloqueia a partição; nunca descarta nem avança o offset. */
class ConsumoKafkaBloqueioTest {
    private static final String TOPICO = "risco.resultados.v1";
    private static final TopicPartition P0 = new TopicPartition(TOPICO, 0);

    @Test
    void bloqueioRepeteIndefinidamenteSemDescartarNemConfirmar() {
        var consumidor = new MockConsumer<String, byte[]>("earliest");
        consumidor.assign(List.of(P0));
        consumidor.updateBeginningOffsets(Map.of(P0, 0L));
        var metricas = new SimpleMeterRegistry();
        var rejeitados = new ArrayList<String>();
        var consumo = new ConsumoKafka(() -> consumidor, TOPICO, registro -> {
            throw new ParticaoBloqueadaException("RESULTADO_CANCELAMENTO_INVALIDO");
        }, (topico, particao, offset, motivo) -> rejeitados.add(motivo), metricas);
        int tentativas = ConsumoKafka.TENTATIVAS_MAXIMAS + 5;
        for (int i = 0; i < tentativas; i++) {
            consumidor.addRecord(new ConsumerRecord<>(TOPICO, 0, 0, "CONTA-002", "x".getBytes()));
            assertThrows(ParticaoBloqueadaException.class, () -> consumo.ciclo(consumidor));
            assertEquals(0, consumidor.position(P0), "volta ao registro bloqueado");
            var confirmado = consumidor.committed(java.util.Set.of(P0)).get(P0);
            assertTrue(confirmado == null || confirmado.offset() == 0, "offset não avança");
        }
        assertEquals(List.of(), rejeitados, "nada é descartado");
        assertEquals(tentativas, metricas.counter("prospero.consumo.bloqueadas", "topico", TOPICO,
                "motivo", "RESULTADO_CANCELAMENTO_INVALIDO").count());
        assertNull(metricas.find("prospero.consumo.descartadas").counter());
    }
}
