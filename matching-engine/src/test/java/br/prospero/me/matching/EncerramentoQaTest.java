package br.prospero.me.matching;

import br.prospero.me.matching.helper.EnvelopeMatching;
import br.prospero.me.matching.helper.SessaoMatching;
import br.prospero.me.matching.kafka.PublicacaoMatching;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static br.prospero.me.matching.EncerramentoIntegracaoTest.encerramento;
import static br.prospero.me.matching.EncerramentoIntegracaoTest.encerrar;
import static br.prospero.me.matching.StopsIntegracaoTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** QA independente da RF05c.4b no ME: bordas do encerramento, contrato com o MO e replay parcial. */
class EncerramentoQaTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Bytes reais gerados por EnvelopeEncerramento.serializar do MO (instante truncado em micros, sem traceparent). */
    static final String COMANDO_MO = "{\"id\":\"" + encerramento("ALFA3", "2026-10-18") + "\",\"tipo\":\"ENCERRAR_SESSAO\",\"versao\":1,"
            + "\"origem\":\"middle-office\",\"correlacao\":\"" + encerramento("ALFA3", "2026-10-18")
            + "\",\"ocorridoEm\":\"2026-10-18T21:00:00.123456Z\",\"traceparent\":null,"
            + "\"dados\":{\"ticker\":\"ALFA3\",\"dataSessao\":\"2026-10-18\"}}";

    private static List<JsonNode> ler(List<byte[]> bytes) throws Exception {
        List<JsonNode> r = new ArrayList<>();
        for (byte[] b : bytes) r.add(JSON.readTree(b));
        return r;
    }

    private static List<String> tipos(List<JsonNode> eventos) {
        return eventos.stream().map(e -> e.path("tipo").asText()).toList();
    }

    @Test
    void comandoSerializadoPeloMoEAceitoPeloParserDoMe() {
        var c = EnvelopeMatching.comando(COMANDO_MO.getBytes(StandardCharsets.UTF_8), "ALFA3");
        assertEquals(encerramento("ALFA3", "2026-10-18"), c.id());
        assertEquals("ALFA3", c.encerramento().ticker());
        assertEquals("2026-10-18", c.sessao().toString());
        assertNull(c.traceparent());
        assertNull(c.admissao());
    }

    @Test
    void encerramentoAntesDeQualquerAdmissaoPublicaSoOMarcoERejeitaTardias() throws Exception {
        var s = new SessaoMatching();
        var marco = ler(s.processar(EnvelopeMatching.comando(encerrar("ALFA3", "2026-10-18"), "ALFA3")));
        assertEquals(List.of("SESSAO_ENCERRADA"), tipos(marco));
        assertEquals(0, marco.getFirst().path("dados").path("expiradas").asLong());
        assertEquals("2026-10-18", s.sessao().toString(), "encerramento fixa a sessão do log");
        var tardia = processar(s, comando("tardia", "LIMIT", "COMPRA", 1, "10.00", null));
        assertEquals("ORDEM_REJEITADA", tardia.getFirst().path("tipo").asText());
        assertEquals("SESSAO_ENCERRADA", tardia.getFirst().path("dados").path("motivo").asText());
        assertEquals(1, tardia.getFirst().path("versao").asInt());
        var stopTardio = processar(s, comando("stopTardio", "STOP", "VENDA", 1, null, "9.00"));
        assertEquals("ORDEM_REJEITADA", stopTardio.getFirst().path("tipo").asText());
        assertEquals(2, stopTardio.getFirst().path("versao").asInt(), "rejeição tardia segue a versão da ordem");
        assertTrue(s.processar(EnvelopeMatching.comando(encerrar("ALFA3", "2026-10-18"), "ALFA3")).isEmpty());
    }

    @Test
    void stopLimitAcionadoRepousandoEStopPendenteExpiramEmV4NaOrdemDeChegada() throws Exception {
        var s = new SessaoMatching();
        processar(s, comando("v1", "LIMIT", "VENDA", 1, "10.00", null));
        processar(s, comando("c1", "LIMIT", "COMPRA", 1, "10.00", null));
        processar(s, comando("pend", "STOP", "VENDA", 2, null, "9.00"));
        var acionado = processar(s, comando("sl", "STOP_LIMIT", "COMPRA", 3, "10.20", "10.00"));
        assertTrue(tipos(acionado).contains("STOP_ACIONADO"), tipos(acionado).toString());
        assertFalse(tipos(acionado).contains("ORDEM_ENCERRADA"), "Stop Limit acionado repousa sem contraparte");
        var fim = ler(s.processar(EnvelopeMatching.comando(encerrar("ALFA3", "2026-10-18"), "ALFA3")));
        assertEquals(List.of("ORDEM_ENCERRADA", "ORDEM_ENCERRADA", "SESSAO_ENCERRADA"), tipos(fim));
        assertEquals(id("pend").toString(), fim.get(0).path("dados").path("ordem").asText(), "pendente chegou antes");
        assertEquals(admissao("pend").toString(), fim.get(0).path("correlacao").asText());
        assertEquals(id("sl").toString(), fim.get(1).path("dados").path("ordem").asText());
        assertEquals(admissao("sl").toString(), fim.get(1).path("correlacao").asText());
        assertEquals(0, fim.get(1).path("dados").path("executada").asLong());
        assertEquals(3, fim.get(1).path("dados").path("cancelada").asLong());
        for (var e : fim) {
            assertEquals(4, e.path("versao").asInt());
            assertEquals(encerramento("ALFA3", "2026-10-18").toString(), e.path("causacao").asText());
        }
        assertEquals(2, fim.getLast().path("dados").path("expiradas").asLong());
    }

    @Test
    void publicacaoParcialDoEncerramentoNaoConfirmaEReplayRepublicaOsMesmosBytes() throws Exception {
        List<byte[]> log = List.of(comando("compra", "LIMIT", "COMPRA", 2, "9.00", null),
                comando("pend", "STOP", "VENDA", 2, null, "8.00"), encerrar("ALFA3", "2026-10-18"));
        var a = new SessaoMatching();
        List<byte[]> ultimo = List.of();
        for (byte[] c : log) ultimo = a.processar(EnvelopeMatching.comando(c, "ALFA3"));
        List<byte[]> eventos = ultimo;
        assertEquals(3, eventos.size());
        AtomicInteger envios = new AtomicInteger(), confirmacoes = new AtomicInteger();
        List<byte[]> enviados = new ArrayList<>();
        assertThrows(IOException.class, () -> PublicacaoMatching.publicar(eventos, e -> {
            if (envios.incrementAndGet() == 2) throw new IOException("ack interrompido");
            enviados.add(e);
        }, confirmacoes::incrementAndGet));
        assertEquals(0, confirmacoes.get(), "checkpoint não avança com o marco não publicado");
        var b = new SessaoMatching();
        List<byte[]> replay = List.of();
        for (byte[] c : log) replay = b.processar(EnvelopeMatching.comando(c, "ALFA3"));
        assertEquals(eventos.size(), replay.size());
        for (int i = 0; i < eventos.size(); i++) assertArrayEquals(eventos.get(i), replay.get(i), "evento " + i);
        assertArrayEquals(enviados.getFirst(), replay.getFirst(), "republicação idêntica permite deduplicação por id");
        List<byte[]> publicados = new ArrayList<>();
        PublicacaoMatching.publicar(replay, publicados::add, confirmacoes::incrementAndGet);
        assertEquals(3, publicados.size());
        assertEquals(1, confirmacoes.get());
    }

    /** Fixa os bytes reais do ME copiados para EncerramentoBytesMeTest do MO e do FO (contrato byte a byte). */
    @Test
    void bytesReaisCopiadosParaOsContratosDoMoEFo() throws Exception {
        var s = new SessaoMatching();
        processar(s, comando("compra", "LIMIT", "COMPRA", 2, "9.00", null));
        processar(s, comando("pend", "STOP", "VENDA", 2, null, "8.00"));
        processar(s, OcoIntegracaoTest.venda("par", 4, "12.00", "9.00"));
        List<String> linhas = new ArrayList<>();
        for (byte[] b : s.processar(EnvelopeMatching.comando(encerrar("ALFA3", "2026-10-18"), "ALFA3")))
            linhas.add(new String(b, StandardCharsets.UTF_8));
        for (byte[] b : new SessaoMatching().processar(EnvelopeMatching.comando(encerrar("BETA3", "2026-10-18"), "BETA3")))
            linhas.add(new String(b, StandardCharsets.UTF_8));
        assertEquals(List.of(
                "{\"id\":\"35208740-8811-386c-acd4-89c90de0072d\",\"versao\":4,\"origem\":\"matching-engine\",\"correlacao\":\"7a851961-bdc0-38e6-8d2c-bbc15425b493\",\"ocorridoEm\":\"2026-10-18T21:00:00Z\",\"ticker\":\"ALFA3\",\"dataSessao\":\"2026-10-18\",\"traceparent\":null,\"causacao\":\"f8e7dad5-395f-3b04-aa8a-19a234cc3bb2\",\"dados\":{\"ordem\":\"570f8e40-738a-3bca-9d84-1bfeb6bfc05f\",\"intencao\":\"57ca3bea-aa20-33ff-ac90-aa7a3247c7d9\",\"conta\":\"CONTA-001\",\"motivo\":\"EXPIRADA_DIA\",\"executada\":0,\"cancelada\":2},\"tipo\":\"ORDEM_ENCERRADA\"}",
                "{\"id\":\"20077cff-6356-37bc-979c-abd203a0c74f\",\"versao\":4,\"origem\":\"matching-engine\",\"correlacao\":\"ee6dc299-247e-3fa7-a330-acc393b199a3\",\"ocorridoEm\":\"2026-10-18T21:00:00Z\",\"ticker\":\"ALFA3\",\"dataSessao\":\"2026-10-18\",\"traceparent\":null,\"causacao\":\"f8e7dad5-395f-3b04-aa8a-19a234cc3bb2\",\"dados\":{\"ordem\":\"f2f530ee-f36a-3d56-b6f7-2e5528a6e2dd\",\"intencao\":\"4e27e0d6-28ef-361a-aaf3-e3f1f7f38495\",\"conta\":\"CONTA-001\",\"motivo\":\"EXPIRADA_DIA\",\"executada\":0,\"cancelada\":2},\"tipo\":\"ORDEM_ENCERRADA\"}",
                "{\"id\":\"a3db2046-79e0-3f63-a2f7-627c66febb36\",\"versao\":4,\"origem\":\"matching-engine\",\"correlacao\":\"d45567d1-1445-3824-9d79-c37402194069\",\"ocorridoEm\":\"2026-10-18T21:00:00Z\",\"ticker\":\"ALFA3\",\"dataSessao\":\"2026-10-18\",\"traceparent\":null,\"causacao\":\"f8e7dad5-395f-3b04-aa8a-19a234cc3bb2\",\"dados\":{\"ordem\":\"69347f7f-e35d-3806-8ca8-9e04421c7f94\",\"intencao\":\"3dffd47e-bb98-3661-9ed0-7ceaed9e71d5\",\"conta\":\"CONTA-002\",\"motivo\":\"EXPIRADA_DIA\",\"executada\":0,\"cancelada\":4},\"tipo\":\"ORDEM_ENCERRADA\"}",
                "{\"id\":\"dd95da59-f952-31e6-88a1-3185a9648f0a\",\"versao\":4,\"origem\":\"matching-engine\",\"correlacao\":\"d45567d1-1445-3824-9d79-c37402194069\",\"ocorridoEm\":\"2026-10-18T21:00:00Z\",\"ticker\":\"ALFA3\",\"dataSessao\":\"2026-10-18\",\"traceparent\":null,\"causacao\":\"f8e7dad5-395f-3b04-aa8a-19a234cc3bb2\",\"dados\":{\"ordem\":\"a86d6ea9-493b-3d7f-9940-bc9ac532e6e1\",\"intencao\":\"3dffd47e-bb98-3661-9ed0-7ceaed9e71d5\",\"conta\":\"CONTA-002\",\"motivo\":\"EXPIRADA_DIA\",\"executada\":0,\"cancelada\":4},\"tipo\":\"ORDEM_ENCERRADA\"}",
                "{\"id\":\"b28c068a-92ea-3243-83e9-42ddfb7922e9\",\"versao\":4,\"origem\":\"matching-engine\",\"correlacao\":\"f8e7dad5-395f-3b04-aa8a-19a234cc3bb2\",\"ocorridoEm\":\"2026-10-18T21:00:00Z\",\"ticker\":\"ALFA3\",\"dataSessao\":\"2026-10-18\",\"traceparent\":null,\"causacao\":\"f8e7dad5-395f-3b04-aa8a-19a234cc3bb2\",\"dados\":{\"expiradas\":4},\"tipo\":\"SESSAO_ENCERRADA\"}",
                "{\"id\":\"7e5f905a-fd79-327f-b96d-b9bd48cc657f\",\"versao\":4,\"origem\":\"matching-engine\",\"correlacao\":\"4e536858-c741-325a-82a1-c030cc944dcb\",\"ocorridoEm\":\"2026-10-18T21:00:00Z\",\"ticker\":\"BETA3\",\"dataSessao\":\"2026-10-18\",\"traceparent\":null,\"causacao\":\"4e536858-c741-325a-82a1-c030cc944dcb\",\"dados\":{\"expiradas\":0},\"tipo\":\"SESSAO_ENCERRADA\"}"), linhas);
    }
}
