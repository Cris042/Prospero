package br.prospero.me.matching;

import br.prospero.me.matching.helper.EnvelopeMatching;
import br.prospero.me.matching.helper.SessaoMatching;
import br.prospero.me.matching.kafka.PublicacaoMatching;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PublicacaoMatchingTest {
    @Test
    void ackParcialNaoAvancaCheckpointEReplayRepublicaMesmosBytes() throws Exception {
        var comando = EnvelopeMatching.comando(EnvelopeMatchingTest.comando(UUID.randomUUID(), UUID.randomUUID(),
                "VENDA", "10.00", "2026-10-18"), "ALFA3");
        List<byte[]> eventos = new SessaoMatching().processar(comando);
        assertTrue(eventos.size() > 1);
        AtomicInteger envios = new AtomicInteger(), checkpoints = new AtomicInteger();
        assertThrows(IOException.class, () -> PublicacaoMatching.publicar(eventos, e -> {
            if (envios.incrementAndGet() == 2) throw new IOException("ack interrompido");
        }, checkpoints::incrementAndGet));
        assertEquals(0, checkpoints.get());
        List<byte[]> replay = new SessaoMatching().processar(comando);
        assertEquals(eventos.size(), replay.size());
        for (int i = 0; i < eventos.size(); i++) assertArrayEquals(eventos.get(i), replay.get(i));
        List<byte[]> publicados = new ArrayList<>();
        PublicacaoMatching.publicar(replay, publicados::add, checkpoints::incrementAndGet);
        assertEquals(replay.size(), publicados.size());
        assertEquals(1, checkpoints.get());
    }

    @Test
    void checkpointSoExecutaDepoisDeTodosOsAcksEPropagaFalha() {
        AtomicInteger acks = new AtomicInteger();
        assertThrows(IOException.class, () -> PublicacaoMatching.publicar(List.of(new byte[]{1}, new byte[]{2}),
                e -> acks.incrementAndGet(), () -> {
                    assertEquals(2, acks.get());
                    throw new IOException("checkpoint interrompido");
                }));
    }

    @Test
    void comandoSemEventosTambemConfirmaCheckpoint() throws Exception {
        AtomicInteger checkpoints = new AtomicInteger();
        PublicacaoMatching.publicar(List.of(), e -> fail("nenhum envio esperado"), checkpoints::incrementAndGet);
        assertEquals(1, checkpoints.get());
    }

    @Test
    void loteDespachaTodosAntesDeEsperarAcksEMantemOrdemECheckpointPorUltimo() throws Exception {
        List<String> passos = new ArrayList<>();
        PublicacaoMatching.publicarEmLote(List.of(new byte[]{1}, new byte[]{2}, new byte[]{3}), e -> {
            passos.add("envio " + e[0]);
            return () -> passos.add("ack " + e[0]);
        }, () -> passos.add("checkpoint"));
        assertEquals(List.of("envio 1", "envio 2", "envio 3", "ack 1", "ack 2", "ack 3", "checkpoint"), passos);
    }

    @Test
    void loteComAckFalhoNaoConfirmaCheckpoint() {
        AtomicInteger checkpoints = new AtomicInteger();
        assertThrows(IOException.class, () -> PublicacaoMatching.publicarEmLote(List.of(new byte[]{1}, new byte[]{2}),
                e -> () -> { if (e[0] == 2) throw new IOException("ack recusado"); }, checkpoints::incrementAndGet));
        assertEquals(0, checkpoints.get());
    }
}
