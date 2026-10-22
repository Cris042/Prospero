package br.prospero.fo.cotacao;

import br.prospero.fo.cotacao.kafka.CicloCotacao;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CicloCotacaoTest {
    private static final String TOPICO = EnvelopeMatching.TOPICO;
    private static final TopicPartition P0 = new TopicPartition(TOPICO, 0);
    private static final TopicPartition P1 = new TopicPartition(TOPICO, 1);

    private static MockConsumer<String, byte[]> consumidor() {
        var c = new MockConsumer<String, byte[]>("earliest");
        c.assign(List.of(P0, P1));
        c.updateBeginningOffsets(Map.of(P0, 0L, P1, 0L));
        c.updateEndOffsets(Map.of(P0, 1L, P1, 0L));
        return c;
    }

    private static byte[] oferta() {
        return """
                {"id":"%s","tipo":"OFERTA_NO_LIVRO","versao":1,"origem":"matching-engine",
                "correlacao":"%s","ocorridoEm":"2026-10-22T12:00:00Z","ticker":"ALFA3",
                "dataSessao":"2026-10-22","traceparent":null,
                "dados":{"ordem":"%s","intencao":"%s","conta":"CONTA-002",
                "lado":"VENDA","preco":"10.00","restante":10}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void confirmaSomenteAposAplicacaoEAlcancaFimDeTodasParticoes() {
        try (var c = consumidor()) {
            var aplicados = new AtomicInteger();
            var ciclo = new CicloCotacao(c, evento -> {
                assertNull(c.committed(Set.of(P0)).get(P0), "Nada confirmado antes da persistência");
                aplicados.incrementAndGet();
            });
            assertFalse(ciclo.emDia());
            c.addRecord(new ConsumerRecord<>(TOPICO, 0, 0, "ALFA3", oferta()));
            ciclo.rodada();
            assertEquals(1, aplicados.get());
            assertEquals(1, c.committed(Set.of(P0)).get(P0).offset());
            assertTrue(ciclo.emDia());
            ciclo.invalidar();
            assertFalse(ciclo.emDia());
        }
    }

    @Test
    void particaoAindaAtrasadaImpedeEmDiaMesmoComOutraAtualizada() {
        try (var c = consumidor()) {
            c.updateEndOffsets(Map.of(P0, 1L, P1, 2L));
            c.addRecord(new ConsumerRecord<>(TOPICO, 0, 0, "ALFA3", oferta()));
            var ciclo = new CicloCotacao(c, evento -> { });
            ciclo.rodada();
            assertFalse(ciclo.emDia());
        }
    }

    @Test
    void falhaAplicacaoNaoConfirmaEReposicionaParaReplay() {
        try (var c = consumidor()) {
            c.addRecord(new ConsumerRecord<>(TOPICO, 0, 0, "ALFA3", oferta()));
            var ciclo = new CicloCotacao(c, evento -> { throw new IllegalStateException("transitória"); });
            assertThrows(IllegalStateException.class, ciclo::rodada);
            assertFalse(ciclo.emDia());
            assertNull(c.committed(Set.of(P0)).get(P0));
            assertEquals(0, c.position(P0));
        }
    }

    @Test
    void envelopeInvalidoNaoChegaAoActorNemConfirmaOffset() {
        try (var c = consumidor()) {
            var aplicados = new AtomicInteger();
            c.addRecord(new ConsumerRecord<>(TOPICO, 0, 0, "ALFA3", "{}".getBytes(StandardCharsets.UTF_8)));
            var ciclo = new CicloCotacao(c, evento -> aplicados.incrementAndGet());
            assertThrows(RuntimeException.class, ciclo::rodada);
            assertEquals(0, aplicados.get()); assertFalse(ciclo.emDia());
            assertNull(c.committed(Set.of(P0)).get(P0)); assertEquals(0, c.position(P0));
        }
    }

    @Test
    void semParticaoAtribuidaNaoAfirmaAtualizacao() {
        try (var c = new MockConsumer<String, byte[]>("earliest")) {
            var ciclo = new CicloCotacao(c, evento -> { });
            ciclo.iniciar();
            ciclo.rodada();
            assertFalse(ciclo.emDia());
        }
    }

    @Test
    void rebalanceamentoInvalidaEstadoAnterior() {
        try (var c = new MockConsumer<String, byte[]>("earliest")) {
            c.updateBeginningOffsets(Map.of(P0, 0L)); c.updateEndOffsets(Map.of(P0, 0L));
            var ciclo = new CicloCotacao(c, evento -> { });
            ciclo.iniciar(); c.rebalance(List.of(P0)); ciclo.rodada();
            assertTrue(ciclo.emDia());
            c.rebalance(List.of());
            assertFalse(ciclo.emDia());
        }
    }
}
