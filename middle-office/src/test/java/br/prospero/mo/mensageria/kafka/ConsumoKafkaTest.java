package br.prospero.mo.mensageria.kafka;

import br.prospero.mo.mensageria.dominio.MensagemInvalidaException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Commit e reposicionamento do laço de consumo (ADR-012) com MockConsumer. O MockConsumer não reentrega sozinho após
 * seek: o teste republica a partir da posição, como o broker faria no próximo poll.
 */
class ConsumoKafkaTest {
    private static final String TOPICO = "risco.comandos.v1";
    private static final TopicPartition P0 = new TopicPartition(TOPICO, 0);
    private static final TopicPartition P1 = new TopicPartition(TOPICO, 1);
    private static final TopicPartition P2 = new TopicPartition(TOPICO, 2);
    private final MockConsumer<String, byte[]> consumidor = new MockConsumer<>("earliest");
    private final List<String> processados = new ArrayList<>();
    private final List<String> rejeitados = new ArrayList<>();
    private final Map<String, Integer> falhasProgramadas = new HashMap<>();
    private ConsumoKafka consumo;

    @BeforeEach
    void preparar() {
        consumidor.assign(List.of(P0, P1, P2));
        consumidor.updateBeginningOffsets(Map.of(P0, 0L, P1, 0L, P2, 0L));
        consumo = new ConsumoKafka(() -> consumidor, TOPICO, this::processar,
                (topico, particao, offset, motivo) -> rejeitados.add(particao + ":" + offset + ":" + motivo),
                new SimpleMeterRegistry());
    }

    @Test
    void financeiroNaoDescartaMesmoAposDezFalhas() {
        consumo = new ConsumoKafka(() -> consumidor, TOPICO, this::processar,
                (t,p,o,m) -> rejeitados.add(m), new SimpleMeterRegistry(), true);
        falhasProgramadas.put("0:0", 12);
        for (int tentativa=0; tentativa<12; tentativa++) {
            publicar(P0,0,"ok");
            assertThrows(IllegalStateException.class, () -> consumo.ciclo(consumidor));
            assertEquals(0, consumidor.position(P0));
        }
        assertEquals(0, rejeitados.size());
    }

    @Test
    void financeiroInvalidoNaoAvancaOffset() {
        consumo = new ConsumoKafka(() -> consumidor, TOPICO, this::processar,
                (t,p,o,m) -> rejeitados.add(m), new SimpleMeterRegistry(), true);
        publicar(P0,0,"invalida");
        assertThrows(MensagemInvalidaException.class, () -> consumo.ciclo(consumidor));
        assertEquals(0, consumidor.position(P0));
        assertEquals(1, rejeitados.size());
        org.junit.jupiter.api.Assertions.assertFalse(consumo.saudavel());
    }

    private void processar(ConsumerRecord<String, byte[]> registro) {
        String id = registro.partition() + ":" + registro.offset();
        if (new String(registro.value()).equals("invalida")) {
            throw new MensagemInvalidaException("json");
        }
        int restantes = falhasProgramadas.getOrDefault(id, 0);
        if (restantes > 0) {
            falhasProgramadas.put(id, restantes - 1);
            throw new IllegalStateException("transitória");
        }
        processados.add(id);
    }

    private void publicar(TopicPartition p, long offset, String valor) {
        consumidor.addRecord(new ConsumerRecord<>(TOPICO, p.partition(), offset, "CONTA-001", valor.getBytes()));
    }

    /** Republica o que ainda não foi consumido, a partir da posição corrente de cada partição. */
    private void reentregar(Map<TopicPartition, Integer> tamanhos) {
        tamanhos.forEach((p, n) -> {
            for (long o = consumidor.position(p); o < n; o++) {
                publicar(p, o, "ok");
            }
        });
    }

    private long confirmado(TopicPartition p) {
        var c = consumidor.committed(java.util.Set.of(p)).get(p);
        return c == null ? 0 : c.offset();
    }

    @Test
    void falhaNoMeioConfirmaOAnteriorERepeteDoRegistroQueFalhou() {
        Map<TopicPartition, Integer> tamanhos = Map.of(P0, 2, P1, 3, P2, 1);
        tamanhos.forEach((p, n) -> IntStream.range(0, n).forEach(o -> publicar(p, o, "ok")));
        falhasProgramadas.put("1:1", 1);
        assertThrows(IllegalStateException.class, () -> consumo.ciclo(consumidor));
        assertEquals(1, consumidor.position(P1), "volta ao registro que falhou");
        assertEquals(confirmado(P1), consumidor.position(P1), "confirma só o que teve efeito");
        reentregar(tamanhos);
        consumo.ciclo(consumidor);
        assertEquals(6, processados.size(), "cada registro tem efeito exatamente uma vez");
        assertEquals(6, processados.stream().distinct().count());
        assertEquals(List.of("1:0", "1:1", "1:2"), processados.stream().filter(id -> id.startsWith("1:")).toList(),
                "ordem preservada na partição");
        assertEquals(2, confirmado(P0));
        assertEquals(3, confirmado(P1));
        assertEquals(1, confirmado(P2));
    }

    @Test
    void mensagemInvalidaERegistradaEOConsumoAvanca() {
        publicar(P0, 0, "invalida");
        publicar(P0, 1, "ok");
        consumo.ciclo(consumidor);
        assertEquals(List.of("0:0:json"), rejeitados);
        assertEquals(List.of("0:1"), processados);
        assertEquals(2, confirmado(P0));
    }

    @Test
    void falhaPersistenteEDescartadaDepoisDoLimiteSemTravarAParticao() {
        falhasProgramadas.put("2:0", Integer.MAX_VALUE);
        publicar(P2, 0, "ok");
        publicar(P2, 1, "ok");
        for (int i = 1; i < ConsumoKafka.TENTATIVAS_MAXIMAS; i++) {
            assertThrows(IllegalStateException.class, () -> consumo.ciclo(consumidor));
            assertEquals(0, consumidor.position(P2));
            reentregar(Map.of(P2, 2));
        }
        consumo.ciclo(consumidor);
        assertEquals(List.of("2:0:falha persistente: IllegalStateException"), rejeitados);
        assertEquals(List.of("2:1"), processados);
        assertEquals(2, confirmado(P2));
    }
}
