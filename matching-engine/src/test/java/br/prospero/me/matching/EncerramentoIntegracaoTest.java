package br.prospero.me.matching;

import br.prospero.me.matching.helper.EnvelopeMatching;
import br.prospero.me.matching.helper.SessaoMatching;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static br.prospero.me.matching.StopsIntegracaoTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** RF05c.4b: comando ENCERRAR_SESSAO publica expirações v4 e o marco, de forma idempotente e reproduzível. */
class EncerramentoIntegracaoTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static UUID encerramento(String ticker, String data) {
        return UUID.nameUUIDFromBytes(("encerramento:" + ticker + ":" + data).getBytes(StandardCharsets.UTF_8));
    }

    /** Mesmo formato que o MO grava na outbox (`EnvelopeEncerramento`). */
    static byte[] encerrar(String ticker, String data) throws Exception {
        ObjectNode r = JSON.createObjectNode();
        String id = encerramento(ticker, data).toString();
        r.put("id", id); r.put("tipo", "ENCERRAR_SESSAO"); r.put("versao", 1); r.put("origem", "middle-office");
        r.put("correlacao", id); r.put("ocorridoEm", "2026-10-18T21:00:00Z"); r.putNull("traceparent");
        r.putObject("dados").put("ticker", ticker).put("dataSessao", data);
        return JSON.writeValueAsBytes(r);
    }

    private static List<JsonNode> processar(SessaoMatching s, byte[] c, String chave) throws Exception {
        List<JsonNode> r = new ArrayList<>();
        for (byte[] b : s.processar(EnvelopeMatching.comando(c, chave))) r.add(JSON.readTree(b));
        return r;
    }

    @Test
    void encerramentoExpiraOfertaEPublicaMarcoV4Idempotente() throws Exception {
        var s = new SessaoMatching();
        processar(s, comando("compra", "LIMIT", "COMPRA", 2, "9.00", null), "ALFA3");
        var eventos = processar(s, encerrar("ALFA3", "2026-10-18"), "ALFA3");
        assertEquals(List.of("ORDEM_ENCERRADA", "SESSAO_ENCERRADA"), eventos.stream().map(e -> e.path("tipo").asText()).toList());
        UUID causa = encerramento("ALFA3", "2026-10-18");
        JsonNode exp = eventos.getFirst();
        assertEquals(4, exp.path("versao").asInt());
        assertEquals("EXPIRADA_DIA", exp.path("dados").path("motivo").asText());
        assertEquals(0, exp.path("dados").path("executada").asLong());
        assertEquals(2, exp.path("dados").path("cancelada").asLong());
        assertEquals(admissao("compra").toString(), exp.path("correlacao").asText());
        assertEquals(causa.toString(), exp.path("causacao").asText());
        assertEquals("2026-10-18T21:00:00Z", exp.path("ocorridoEm").asText());
        JsonNode marco = eventos.getLast();
        assertEquals(4, marco.path("versao").asInt());
        assertEquals(causa.toString(), marco.path("correlacao").asText());
        assertEquals(causa.toString(), marco.path("causacao").asText());
        assertEquals(1, marco.path("dados").path("expiradas").asLong());
        assertEquals(Set.of("expiradas"), Set.copyOf(nomes(marco.path("dados"))));
        assertEquals(UUID.nameUUIDFromBytes(("matching:" + causa + ":1").getBytes(StandardCharsets.UTF_8)).toString(),
                marco.path("id").asText(), "id do marco derivado da causação");
        assertTrue(processar(s, encerrar("ALFA3", "2026-10-18"), "ALFA3").isEmpty(), "repetição inócua");
        var tardia = processar(s, comando("tardia", "LIMIT", "VENDA", 1, "10.00", null), "ALFA3");
        assertEquals("ORDEM_REJEITADA", tardia.getFirst().path("tipo").asText());
        assertEquals("SESSAO_ENCERRADA", tardia.getFirst().path("dados").path("motivo").asText());
        assertEquals(1, tardia.getFirst().path("versao").asInt());
    }

    @Test
    void livroVazioPublicaSoOMarcoEDataDivergenteNaoTemEfeito() throws Exception {
        var s = new SessaoMatching();
        processar(s, comando("compra", "LIMIT", "COMPRA", 2, "9.00", null), "ALFA3");
        assertTrue(processar(s, encerrar("BETA3", "2026-10-19"), "BETA3").isEmpty(), "outra data da sessão do log");
        var vazio = processar(s, encerrar("BETA3", "2026-10-18"), "BETA3");
        assertEquals(List.of("SESSAO_ENCERRADA"), vazio.stream().map(e -> e.path("tipo").asText()).toList());
        assertEquals(0, vazio.getFirst().path("dados").path("expiradas").asLong());
    }

    @Test
    void parOcoExpiraAsDuasPernasEmV4EReplayReproduzBytes() throws Exception {
        List<byte[]> comandos = List.of(OcoIntegracaoTest.venda("par", 4, "12.00", "9.00"),
                comando("compra", "LIMIT", "COMPRA", 1, "12.00", null), encerrar("ALFA3", "2026-10-18"));
        var a = new SessaoMatching();
        var b = new SessaoMatching();
        List<byte[]> ultimo = List.of();
        for (var c : comandos) {
            var x = a.processar(EnvelopeMatching.comando(c, "ALFA3"));
            var y = b.processar(EnvelopeMatching.comando(c, "ALFA3"));
            assertEquals(x.size(), y.size());
            for (int i = 0; i < x.size(); i++) assertArrayEquals(x.get(i), y.get(i));
            ultimo = x;
        }
        List<JsonNode> fim = new ArrayList<>();
        for (byte[] e : ultimo) fim.add(JSON.readTree(e));
        assertEquals(List.of("ORDEM_ENCERRADA", "ORDEM_ENCERRADA", "SESSAO_ENCERRADA"),
                fim.stream().map(e -> e.path("tipo").asText()).toList());
        assertEquals(id("tp:par").toString(), fim.get(0).path("dados").path("ordem").asText());
        assertEquals(1, fim.get(0).path("dados").path("executada").asLong());
        assertEquals(3, fim.get(0).path("dados").path("cancelada").asLong());
        assertEquals(id("sl:par").toString(), fim.get(1).path("dados").path("ordem").asText());
        assertEquals(3, fim.get(1).path("dados").path("cancelada").asLong(), "SL já reduzido");
        assertTrue(fim.stream().allMatch(e -> e.path("versao").asInt() == 4));
    }

    @Test
    void fronteiraRecusaComandoDeEncerramentoMalFormado() throws Exception {
        String valido = new String(encerrar("ALFA3", "2026-10-18"), StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> EnvelopeMatching.comando(valido.getBytes(StandardCharsets.UTF_8), "ALFA3"));
        String outroId = encerramento("BETA3", "2026-10-18").toString();
        for (String invalido : List.of(valido.replace("\"versao\":1", "\"versao\":2"),
                valido.replace("\"origem\":\"middle-office\"", "\"origem\":\"front-office\""),
                valido.replaceFirst(encerramento("ALFA3", "2026-10-18").toString(), outroId),
                valido.replace("\"dataSessao\":\"2026-10-18\"", "\"dataSessao\":\"2026-10-18\",\"extra\":1"),
                valido.replace("\"dataSessao\":\"2026-10-18\"", "\"dataSessao\":\"18-10-2026\""))) {
            assertThrows(IllegalArgumentException.class,
                    () -> EnvelopeMatching.comando(invalido.getBytes(StandardCharsets.UTF_8), "ALFA3"), invalido);
        }
        assertThrows(IllegalArgumentException.class,
                () -> EnvelopeMatching.comando(valido.getBytes(StandardCharsets.UTF_8), "BETA3"), "chave diferente do ticker");
    }

    private static List<String> nomes(JsonNode no) {
        List<String> lista = new ArrayList<>();
        no.fieldNames().forEachRemaining(lista::add);
        return lista;
    }
}
