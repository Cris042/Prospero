package br.prospero.me.matching;

import br.prospero.me.matching.helper.EnvelopeMatching;
import br.prospero.me.matching.helper.SessaoMatching;
import br.prospero.me.matching.kafka.PublicacaoMatching;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static br.prospero.me.matching.OcoIntegracaoTest.venda;
import static br.prospero.me.matching.StopsIntegracaoTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** QA independente da RF05c.3b1: replay com publicação parcial, v2 byte a byte, cascata e limite de pendentes no par. */
class OcoIntegracaoQaTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<byte[]> log() throws Exception {
        return List.of(venda("par-qa", 4, "12.00", "9.00"),
                comando("compra-qa", "LIMIT", "COMPRA", 1, "12.00", null),
                comando("apoio-qa", "LIMIT", "COMPRA", 2, "9.00", null),
                comando("gatilho-qa", "LIMIT", "VENDA", 1, "9.00", null));
    }

    @Test
    void publicacaoParcialDoParNaoAvancaCheckpointEReplayRepublicaMesmosBytesSemEventoNaReentrega() throws Exception {
        List<byte[]> log = log();
        var original = new SessaoMatching();
        List<List<byte[]>> publicados = new ArrayList<>();
        for (byte[] c : log) publicados.add(original.processar(EnvelopeMatching.comando(c, "ALFA3")));
        assertTrue(publicados.getFirst().size() >= 4, "par gera aceite das duas pernas, oferta e pendente");
        AtomicInteger envios = new AtomicInteger(), checkpoints = new AtomicInteger();
        assertThrows(IOException.class, () -> PublicacaoMatching.publicar(publicados.getFirst(), e -> {
            if (envios.incrementAndGet() == 3) throw new IOException("ack parcial do par");
        }, checkpoints::incrementAndGet));
        assertEquals(0, checkpoints.get(), "checkpoint não avança com o par publicado pela metade");

        var replay = new SessaoMatching();
        for (int i = 0; i < log.size(); i++) {
            List<byte[]> repetidos = replay.processar(EnvelopeMatching.comando(log.get(i), "ALFA3"));
            assertEquals(publicados.get(i).size(), repetidos.size(), "comando " + i);
            for (int j = 0; j < repetidos.size(); j++) assertArrayEquals(publicados.get(i).get(j), repetidos.get(j));
        }
        assertTrue(replay.processar(EnvelopeMatching.comando(log.getFirst(), "ALFA3")).isEmpty(),
                "reentrega do par depois de captura e negócios não gera eventos");
        var tipos = publicados.getLast().stream().map(b -> leitura(b).path("tipo").asText()).toList();
        assertTrue(tipos.contains("STOP_ACIONADO"), "gatilho captura o SL: " + tipos);
        int cancelTp = indice(publicados.getLast(), "CANCELADA_OCO"), acionado = tipos.indexOf("STOP_ACIONADO");
        assertTrue(cancelTp >= 0 && cancelTp < acionado, "TP é cancelado antes do STOP_ACIONADO: " + tipos);
        for (byte[] b : publicados.getLast()) {
            JsonNode e = leitura(b);
            String ordem = e.path("dados").path("ordem").asText();
            if (ordem.equals(id("sl:par-qa").toString()) || ordem.equals(id("tp:par-qa").toString())) {
                assertEquals(3, e.path("versao").asInt(), e.toString());
                assertEquals(admissao("par-qa").toString(), e.path("correlacao").asText());
                assertEquals(admissao("gatilho-qa").toString(), e.path("causacao").asText());
            }
        }
    }

    /** Fixture gerada executando este mesmo log no serializer da base f380fc9 (antes da v3). */
    @Test
    void logHistoricoDeStopsConservaBytesV2() throws Exception {
        List<byte[]> log = List.of(comando("v2-stop", "STOP", "COMPRA", 2, null, "10.00"),
                comando("v2-sl", "STOP_LIMIT", "VENDA", 1, "9.50", "9.60"),
                comando("v2-oferta", "LIMIT", "VENDA", 3, "10.00", null),
                comando("v2-raiz", "LIMIT", "COMPRA", 1, "10.00", null),
                comando("v2-apoio", "LIMIT", "COMPRA", 2, "9.50", null),
                comando("v2-queda", "LIMIT", "VENDA", 2, "9.50", null),
                comando("v2-simples", "LIMIT", "VENDA", 1, "10.00", null));
        var sessao = new SessaoMatching();
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        int eventos = 0;
        for (byte[] c : log) {
            for (byte[] evento : sessao.processar(EnvelopeMatching.comando(c, "ALFA3"))) {
                digest.update(evento); digest.update((byte) '\n'); eventos++;
                assertTrue(leitura(evento).path("versao").asInt() <= 2);
            }
        }
        String hash = java.util.HexFormat.of().formatHex(digest.digest());
        assertEquals("FIXTURE_EVENTOS=" + FIXTURE_EVENTOS + " FIXTURE_HASH=" + FIXTURE_HASH,
                "FIXTURE_EVENTOS=" + eventos + " FIXTURE_HASH=" + hash);
    }

    static final int FIXTURE_EVENTOS = 23;
    static final String FIXTURE_HASH = "5af149d341d3a5434b267dfe91d2d04d7135bc40044e4c7ea4eef0ca436a51a8";

    @Test
    void slCapturadoAlemDaCascataCancelaTpAntesDoLimiteCascataAmbosV3() throws Exception {
        var s = new SessaoMatching();
        for (int i = 0; i < 32; i++) processar(s, comando("cascata" + i, "STOP", "VENDA", 1, null, "9.00"));
        processar(s, venda("par-cascata", 2, "12.00", "9.00"));
        processar(s, comando("bid-cascata", "LIMIT", "COMPRA", 1, "9.00", null));
        var eventos = processar(s, comando("raiz-cascata", "LIMIT", "VENDA", 1, "9.00", null));
        String tp = id("tp:par-cascata").toString(), sl = id("sl:par-cascata").toString();
        int iTp = -1, iSl = -1;
        for (int i = 0; i < eventos.size(); i++) {
            JsonNode d = eventos.get(i).path("dados");
            if (d.path("ordem").asText().equals(tp)) { assertEquals("CANCELADA_OCO", d.path("motivo").asText()); iTp = i; }
            if (d.path("ordem").asText().equals(sl)) { assertEquals("LIMITE_CASCATA", d.path("motivo").asText()); iSl = i; }
        }
        assertTrue(iTp >= 0 && iSl == iTp + 1, "TP CANCELADA_OCO imediatamente antes do LIMITE_CASCATA do SL");
        for (int i : new int[]{iTp, iSl}) {
            assertEquals(3, eventos.get(i).path("versao").asInt());
            assertEquals(admissao("par-cascata").toString(), eventos.get(i).path("correlacao").asText());
            assertEquals(admissao("raiz-cascata").toString(), eventos.get(i).path("causacao").asText());
        }
        assertEquals(2, eventos.get(iTp).path("dados").path("cancelada").asLong());
        assertEquals(2, eventos.get(iSl).path("dados").path("cancelada").asLong());
        assertTrue(eventos.stream().noneMatch(e -> e.path("tipo").asText().equals("STOP_ACIONADO")
                && e.path("dados").path("ordem").asText().equals(sl)));
    }

    @Test
    void parComPendentesLotadosRejeitaAsDuasPernasComLimiteStopsPendentesEmV3() throws Exception {
        var s = new SessaoMatching();
        for (int i = 0; i < 500; i++) processar(s, comando("lotado" + i, "STOP", "COMPRA", 1, null, "20.00"));
        var eventos = processar(s, venda("par-lotado", 2, "12.00", "9.00"));
        assertEquals(2, eventos.size());
        assertEquals(id("tp:par-lotado").toString(), eventos.get(0).path("dados").path("ordem").asText());
        assertEquals(id("sl:par-lotado").toString(), eventos.get(1).path("dados").path("ordem").asText());
        for (JsonNode e : eventos) {
            assertEquals("ORDEM_REJEITADA", e.path("tipo").asText());
            assertEquals("LIMITE_STOPS_PENDENTES", e.path("dados").path("motivo").asText());
            assertEquals(3, e.path("versao").asInt());
        }
    }

    @Test
    void parConsomeDuasVagasDoOrcamentoDeOrdensDaSessao() throws Exception {
        var s = new SessaoMatching();
        for (int i = 0; i < 29_999; i++) s.processar(EnvelopeMatching.comando(comando("orc" + i, "LIMIT", "VENDA", 1, "12.00", null), "ALFA3"));
        byte[] par = venda("par-orcamento", 1, "12.00", "9.00");
        assertThrows(IllegalStateException.class, () -> s.processar(EnvelopeMatching.comando(par, "ALFA3")),
                "29.999 + 2 ordens excede 30.000");
    }

    private static int indice(List<byte[]> eventos, String motivo) {
        for (int i = 0; i < eventos.size(); i++) {
            JsonNode e = leitura(eventos.get(i));
            if (motivo.equals(e.path("dados").path("motivo").asText())
                    && e.path("dados").path("ordem").asText().equals(id("tp:par-qa").toString())) return i;
        }
        return -1;
    }

    private static JsonNode leitura(byte[] b) {
        try { return JSON.readTree(b); } catch (IOException erro) { throw new IllegalStateException(erro); }
    }
}
