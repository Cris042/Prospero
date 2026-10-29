package br.prospero.fo.comum.kafka;

import br.prospero.fo.comum.dominio.MensagemInvalidaException;
import br.prospero.fo.comum.dominio.MensagemQuarentena;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-019: transitória espera sem contar tentativa; permanente vai para a quarentena com payload e o offset avança. */
class QuarentenaConsumoTest {
    private static final String TOPICO = "risco.resultados.v1";
    private static final TopicPartition P0 = new TopicPartition(TOPICO, 0);
    private static final String TRACE = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    private final MockConsumer<String, byte[]> consumidor = new MockConsumer<>("earliest");
    private final List<MensagemQuarentena> quarentena = new ArrayList<>();
    private final List<Long> processados = new ArrayList<>();
    private final SimpleMeterRegistry metricas = new SimpleMeterRegistry();
    private boolean bancoFora;
    private ConsumoKafka consumo;

    @BeforeEach
    void preparar() {
        consumidor.assign(List.of(P0));
        consumidor.updateBeginningOffsets(Map.of(P0, 0L));
        consumo = new ConsumoKafka(() -> consumidor, TOPICO, this::processar, quarentena::add, metricas);
    }

    private void processar(ConsumerRecord<String, byte[]> registro) {
        String valor = new String(registro.value(), StandardCharsets.UTF_8);
        if (valor.equals("invalida")) throw new MensagemInvalidaException("contrato");
        if (bancoFora) throw new IllegalStateException("tx", new SQLTransientConnectionException("fora", "08006"));
        processados.add(registro.offset());
    }

    @Test
    void bancoForaNaoContaTentativaNemVaiParaQuarentenaEOConsumoSegueQuandoVolta() {
        bancoFora = true;
        for (int i = 0; i < ConsumoKafka.TENTATIVAS_MAXIMAS * 2; i++) {
            publicar(0, "ok");
            assertThrows(IllegalStateException.class, () -> consumo.ciclo(consumidor));
            assertEquals(0, consumidor.position(P0), "offset não avança com o banco fora");
        }
        assertTrue(quarentena.isEmpty(), "transitória nunca leva à quarentena");
        assertEquals(20.0, metricas.counter("prospero.consumo.transitorias", "topico", TOPICO).count());
        bancoFora = false;
        publicar(0, "ok");
        consumo.ciclo(consumidor);
        assertEquals(List.of(0L), processados);
        assertEquals(1, consumidor.committed(java.util.Set.of(P0)).get(P0).offset());
    }

    @Test
    void erroPermanenteVaiParaQuarentenaComPayloadChaveETraceparent() {
        var cabecalhos = new RecordHeaders();
        cabecalhos.add("traceparent", TRACE.getBytes(StandardCharsets.UTF_8));
        consumidor.addRecord(new ConsumerRecord<>(TOPICO, 0, 0, ConsumerRecord.NO_TIMESTAMP, TimestampType.NO_TIMESTAMP_TYPE,
                ConsumerRecord.NULL_SIZE, ConsumerRecord.NULL_SIZE, "CHAVE-1", "invalida".getBytes(StandardCharsets.UTF_8),
                cabecalhos, Optional.empty()));
        publicar(1, "ok");
        consumo.ciclo(consumidor);
        assertEquals(1, quarentena.size());
        var m = quarentena.getFirst();
        assertEquals(TOPICO, m.topico());
        assertEquals(0, m.particao());
        assertEquals(0, m.deslocamento());
        assertEquals("CHAVE-1", m.chave());
        assertEquals(TRACE, m.traceparent());
        assertArrayEquals("invalida".getBytes(StandardCharsets.UTF_8), m.payload());
        assertEquals("contrato", m.motivo());
        assertEquals(1, m.tentativas());
        assertEquals(List.of(1L), processados, "a partição segue depois da quarentena");
        assertEquals(2, consumidor.committed(java.util.Set.of(P0)).get(P0).offset());
    }

    @Test
    void quarentenaIndisponivelNaoAvancaOOffset() {
        consumo = new ConsumoKafka(() -> consumidor, TOPICO, this::processar, m -> {
            throw new IllegalStateException("tx", new SQLTransientConnectionException("fora", "08006"));
        }, metricas);
        publicar(0, "invalida");
        assertThrows(IllegalStateException.class, () -> consumo.ciclo(consumidor));
        assertEquals(0, consumidor.position(P0), "sem quarentena gravada a mensagem não é perdida");
    }

    private void publicar(long offset, String valor) {
        consumidor.addRecord(new ConsumerRecord<>(TOPICO, 0, offset, "CHAVE-1", valor.getBytes(StandardCharsets.UTF_8)));
    }
}
