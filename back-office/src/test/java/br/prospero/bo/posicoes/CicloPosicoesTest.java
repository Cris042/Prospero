package br.prospero.bo.posicoes;

import br.prospero.bo.posicoes.dominio.ProjecaoIncoerenteException;
import br.prospero.bo.posicoes.helper.EnvelopeMatching;
import br.prospero.bo.posicoes.kafka.CicloPosicoes;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CicloPosicoesTest {
    private static final String TOPICO = EnvelopeMatching.TOPICO;
    private static final String ID = "ABCDEFGHIJKLMNOPQRSTUV";
    private static final TopicPartition P0 = new TopicPartition(TOPICO, 0);
    private static final TopicPartition P1 = new TopicPartition(TOPICO, 1);
    private static final TopicPartition P2 = new TopicPartition(TOPICO, 2);
    private static final List<TopicPartition> PARTICOES = List.of(P0, P1, P2);

    private static <T extends MockConsumer<String, byte[]>> T configurar(T consumidor, long fim0) {
        consumidor.updateBeginningOffsets(Map.of(P0, 0L, P1, 0L, P2, 0L));
        consumidor.updateEndOffsets(Map.of(P0, fim0, P1, 0L, P2, 0L));
        return consumidor;
    }

    @Test
    void confirmaKafkaSomenteDepoisDePersistirEAlcancaTodasAsParticoes() {
        try (var c = configurar(new MockConsumer<String, byte[]>("earliest"), 1)) {
            AtomicLong banco = new AtomicLong();
            var ciclo = new CicloPosicoes(c, ID, p -> p == 0 ? banco.get() : 0, (coordenada, evento) -> {
                assertNull(c.committed(Set.of(P0)).get(P0));
                assertEquals(0, coordenada.offset());
                assertEquals(ID, coordenada.topicoId());
                banco.incrementAndGet();
            });
            ciclo.iniciar(); c.rebalance(PARTICOES);
            c.addRecord(registro(0));
            ciclo.rodada(() -> { });
            assertEquals(1, banco.get());
            assertEquals(1, c.committed(Set.of(P0)).get(P0).offset());
            assertTrue(ciclo.emDia());
            assertThrows(ProjecaoIncoerenteException.class, () -> c.rebalance(List.of(P0, P1)));
            assertFalse(ciclo.emDia());
        }
    }

    @Test
    void erroTransitorioAoValidarIdentidadeNaoPerdeLoteJaRetornadoPeloPoll() {
        try (var c = configurar(new MockConsumer<String, byte[]>("earliest"), 1)) {
            AtomicLong banco = new AtomicLong();
            var ciclo = new CicloPosicoes(c, ID, p -> p == 0 ? banco.get() : 0, (coordenada, evento) -> banco.incrementAndGet());
            ciclo.iniciar(); c.rebalance(PARTICOES);
            ConsumerRecord<String, byte[]> registro = registro(0);
            c.addRecord(registro);
            assertThrows(IllegalStateException.class, () -> ciclo.rodada(() -> { throw new IllegalStateException("metadados temporariamente indisponíveis"); }));
            assertEquals(0, banco.get());
            assertFalse(ciclo.emDia());
            assertEquals(0, c.position(P0), "O poll não confirma persistência; deve permitir replay do lote");
            assertNull(c.committed(Set.of(P0)).get(P0));
            c.addRecord(registro);
            ciclo.rodada(() -> { });
            assertEquals(1, banco.get());
            assertTrue(ciclo.emDia());
        }
    }

    @Test
    void falhaDoActorReposicionaSemConfirmar() {
        try (var c = configurar(new MockConsumer<String, byte[]>("earliest"), 1)) {
            var ciclo = new CicloPosicoes(c, ID, p -> 0, (coordenada, evento) -> { throw new IllegalStateException("commit abortado"); });
            ciclo.iniciar(); c.rebalance(PARTICOES); c.addRecord(registro(0));
            assertThrows(IllegalStateException.class, () -> ciclo.rodada(() -> { }));
            assertEquals(0, c.position(P0));
            assertNull(c.committed(Set.of(P0)).get(P0));
            assertFalse(ciclo.emDia());
        }
    }

    @Test
    void ackKafkaPerdidoRepeteSomenteTransporteDepoisDoCommitPostgres() {
        try (var c = configurar(new AckFalhaUmaVez(), 1)) {
            AtomicLong banco = new AtomicLong();
            AtomicInteger tentativas = new AtomicInteger();
            var ciclo = new CicloPosicoes(c, ID, p -> p == 0 ? banco.get() : 0, (coordenada, evento) -> {
                tentativas.incrementAndGet();
                if (coordenada.offset() == banco.get()) banco.incrementAndGet();
            });
            ciclo.iniciar(); c.rebalance(PARTICOES);
            ConsumerRecord<String, byte[]> registro = registro(0);
            c.addRecord(registro);
            assertThrows(CommitFailedException.class, () -> ciclo.rodada(() -> { }));
            assertEquals(1, banco.get()); assertFalse(ciclo.emDia());
            assertEquals(0, c.position(P0));
            c.addRecord(registro); ciclo.rodada(() -> { });
            assertEquals(2, tentativas.get());
            assertEquals(1, banco.get());
            assertEquals(1, c.committed(Set.of(P0)).get(P0).offset());
            assertTrue(ciclo.emDia());
        }
    }

    @Test
    void naoDeclaraProntidaoApenasPorquePosicaoKafkaAlcancouFim() {
        try (var c = configurar(new MockConsumer<String, byte[]>("earliest"), 1)) {
            var ciclo = new CicloPosicoes(c, ID, p -> 0, (coordenada, evento) -> { });
            ciclo.iniciar(); c.rebalance(PARTICOES); c.addRecord(registro(0));
            assertThrows(ProjecaoIncoerenteException.class, () -> ciclo.rodada(() -> { }));
            assertFalse(ciclo.emDia(), "O checkpoint PostgreSQL também precisa alcançar o fim");
            assertNull(c.committed(Set.of(P0)).get(P0), "Sem checkpoint não há autorização para confirmar Kafka");
            assertEquals(0, c.position(P0));
        }
    }

    @Test
    void inicioAusenteCheckpointAlemDoLogOuKafkaAFrenteDoBancoBloqueiamBootstrap() {
        try (var c = configurar(new MockConsumer<String, byte[]>("earliest"), 1)) {
            c.updateBeginningOffsets(Map.of(P0, 1L, P1, 0L, P2, 0L));
            var ciclo = new CicloPosicoes(c, ID, p -> 0, (coordenada, evento) -> { });
            ciclo.iniciar(); assertThrows(ProjecaoIncoerenteException.class, () -> c.rebalance(PARTICOES));
        }
        try (var c = configurar(new MockConsumer<String, byte[]>("earliest"), 1)) {
            var ciclo = new CicloPosicoes(c, ID, p -> p == 0 ? 2 : 0, (coordenada, evento) -> { });
            ciclo.iniciar(); assertThrows(ProjecaoIncoerenteException.class, () -> c.rebalance(PARTICOES));
        }
        try (var c = configurar(new MockConsumer<String, byte[]>("earliest"), 1)) {
            var ciclo = new CicloPosicoes(c, ID, p -> 0, (coordenada, evento) -> { });
            ciclo.iniciar(); c.rebalance(PARTICOES);
            c.commitSync(Map.of(P0, new OffsetAndMetadata(1)));
            assertThrows(ProjecaoIncoerenteException.class, () -> c.rebalance(PARTICOES));
        }
    }

    @Test
    void tresParticoesObrigatoriasEParticaoAtrasadaImpedemProntidao() {
        try (var c = configurar(new MockConsumer<String, byte[]>("earliest"), 0)) {
            var ciclo = new CicloPosicoes(c, ID, p -> 0, (coordenada, evento) -> { });
            ciclo.iniciar(); assertThrows(ProjecaoIncoerenteException.class, () -> c.rebalance(List.of(P0, P1)));
        }
        try (var c = configurar(new MockConsumer<String, byte[]>("earliest"), 0)) {
            c.updateEndOffsets(Map.of(P0, 0L, P1, 1L, P2, 0L));
            var ciclo = new CicloPosicoes(c, ID, p -> 0, (coordenada, evento) -> { });
            ciclo.iniciar(); c.rebalance(PARTICOES); ciclo.rodada(() -> { });
            assertFalse(ciclo.emDia());
        }
    }

    @Test
    void envelopeInvalidoNaoChegaAoActorNemConfirma() {
        try (var c = configurar(new MockConsumer<String, byte[]>("earliest"), 1)) {
            AtomicInteger aplicados = new AtomicInteger();
            var ciclo = new CicloPosicoes(c, ID, p -> 0, (coordenada, evento) -> aplicados.incrementAndGet());
            ciclo.iniciar(); c.rebalance(PARTICOES);
            c.addRecord(new ConsumerRecord<>(TOPICO, 0, 0, "ALFA3", "{}".getBytes(StandardCharsets.UTF_8)));
            assertThrows(RuntimeException.class, () -> ciclo.rodada(() -> { }));
            assertEquals(0, aplicados.get()); assertEquals(0, c.position(P0));
            assertNull(c.committed(Set.of(P0)).get(P0)); assertFalse(ciclo.emDia());
        }
    }

    @Test
    void ativoEmParticaoDiferenteDoHashKafkaNaoChegaAoActorNemAvanca() {
        try (var c = configurar(new MockConsumer<String, byte[]>("earliest"), 0)) {
            c.updateEndOffsets(Map.of(P0, 0L, P1, 1L, P2, 0L));
            AtomicLong banco = new AtomicLong();
            AtomicInteger aplicados = new AtomicInteger();
            var ciclo = new CicloPosicoes(c, ID, p -> p == 1 ? banco.get() : 0, (coordenada, evento) -> {
                aplicados.incrementAndGet(); banco.incrementAndGet();
            });
            ciclo.iniciar(); c.rebalance(PARTICOES);
            // ALFA3 pertence à partição zero pelo Murmur2 Kafka com três partições.
            byte[] valido = registro(0).value();
            c.addRecord(new ConsumerRecord<>(TOPICO, 1, 0, "ALFA3", valido));
            assertThrows(ProjecaoIncoerenteException.class, () -> ciclo.rodada(() -> { }));
            assertEquals(0, aplicados.get()); assertEquals(0, banco.get());
            assertNull(c.committed(Set.of(P1)).get(P1));
            assertEquals(0, c.position(P1)); assertFalse(ciclo.emDia());
        }
    }

    private static class AckFalhaUmaVez extends MockConsumer<String, byte[]> {
        private boolean falhar = true;
        AckFalhaUmaVez() { super("earliest"); }
        @Override public synchronized void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            if (falhar) { falhar = false; throw new CommitFailedException("ack perdido na sonda"); }
            super.commitSync(offsets);
        }
    }

    private static ConsumerRecord<String, byte[]> registro(long offset) {
        UUID intencao = UUID.randomUUID();
        UUID correlacao = UUID.nameUUIDFromBytes(("admissao:" + intencao).getBytes(StandardCharsets.UTF_8));
        byte[] bytes = """
                {"id":"%s","tipo":"ORDEM_ACEITA","versao":1,"origem":"matching-engine",
                "correlacao":"%s","ocorridoEm":"2026-10-23T12:00:00Z","ticker":"ALFA3",
                "dataSessao":"2026-10-23","traceparent":null,
                "dados":{"ordem":"%s","intencao":"%s","conta":"CONTA-002","sequencia":1}}
                """.formatted(UUID.randomUUID(), correlacao, UUID.randomUUID(), intencao).getBytes(StandardCharsets.UTF_8);
        return new ConsumerRecord<>(TOPICO, 0, offset, "ALFA3", bytes);
    }
}
