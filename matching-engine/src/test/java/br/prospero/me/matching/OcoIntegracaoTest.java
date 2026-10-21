package br.prospero.me.matching;

import br.prospero.me.matching.helper.EnvelopeMatching;
import br.prospero.me.matching.helper.SessaoMatching;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static br.prospero.me.matching.StopsIntegracaoTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** RF05c.3b1: admissão v3 do par e eventos OCO v3, sem alterar v1/v2. */
class OcoIntegracaoTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static byte[] par(String nome, String lado, int q, String alvo, String tipoStop, String limiteStop, String gatilho,
                      String maximo) throws Exception {
        UUID intencao = id("intencao:" + nome);
        ObjectNode r = JSON.createObjectNode();
        r.put("id", admissao(nome).toString()); r.put("tipo", "ORDEM_ADMITIDA"); r.put("versao", 3);
        r.put("origem", "middle-office"); r.put("correlacao", intencao.toString());
        r.put("ocorridoEm", "2026-10-18T12:00:00Z"); r.put("conta", "CONTA-002"); r.putNull("traceparent");
        ObjectNode d = r.putObject("dados");
        d.put("ticker", "ALFA3"); d.put("lado", lado); d.put("dataSessao", "2026-10-18"); d.put("precoMaximo", maximo);
        ArrayNode pernas = d.putArray("pernas");
        ObjectNode tp = pernas.addObject();
        tp.put("papel", "TAKE_PROFIT"); tp.put("ordem", id("tp:" + nome).toString()); tp.put("tipo", "LIMIT");
        tp.put("validade", "DIA"); tp.put("quantidade", q); tp.put("precoLimite", alvo);
        ObjectNode sl = pernas.addObject();
        sl.put("papel", "STOP_LOSS"); sl.put("ordem", id("sl:" + nome).toString()); sl.put("tipo", tipoStop);
        sl.put("validade", "DIA"); sl.put("quantidade", q); sl.put("precoLimite", limiteStop); sl.put("precoGatilho", gatilho);
        return JSON.writeValueAsBytes(r);
    }

    static byte[] venda(String nome, int q, String alvo, String gatilho) throws Exception {
        return par(nome, "VENDA", q, alvo, "STOP", null, gatilho, null);
    }

    private static List<String> tipos(List<JsonNode> eventos) {
        return eventos.stream().map(e -> e.path("tipo").asText()).toList();
    }

    @Test
    void parV3EmiteEventosV3ComPernaEmDadosOrdem() throws Exception {
        var eventos = processar(new SessaoMatching(), venda("par", 4, "12.00", "9.00"));
        assertEquals(List.of("ORDEM_ACEITA", "ORDEM_ACEITA", "OFERTA_NO_LIVRO", "STOP_PENDENTE"), tipos(eventos));
        for (var e : eventos) {
            assertEquals(3, e.path("versao").asInt());
            assertEquals(admissao("par").toString(), e.path("correlacao").asText());
            assertEquals(admissao("par").toString(), e.path("causacao").asText());
            assertEquals(id("intencao:par").toString(), e.path("dados").path("intencao").asText());
        }
        assertEquals(id("tp:par").toString(), eventos.get(2).path("dados").path("ordem").asText());
        assertEquals(id("sl:par").toString(), eventos.get(3).path("dados").path("ordem").asText());
    }

    @Test
    void reducaoECancelamentoOcoSaoSerializadosEmV3MesmoComAgressorSimples() throws Exception {
        var s = new SessaoMatching();
        processar(s, venda("par", 4, "12.00", "9.00"));
        var parcial = processar(s, comando("compra", "LIMIT", "COMPRA", 1, "12.00", null));
        assertEquals(List.of("ORDEM_ACEITA", "NEGOCIO", "ORDEM_REDUZIDA", "ORDEM_ENCERRADA"), tipos(parcial));
        assertEquals(List.of(1, 3, 3, 1), parcial.stream().map(e -> e.path("versao").asInt()).toList(),
                "fatos só do agressor simples continuam v1");
        JsonNode reducao = parcial.get(2);
        assertEquals(3, reducao.path("versao").asInt());
        assertEquals(id("sl:par").toString(), reducao.path("dados").path("ordem").asText());
        assertEquals(1, reducao.path("dados").path("reduzida").asLong());
        assertEquals(3, reducao.path("dados").path("restante").asLong());
        assertEquals(admissao("par").toString(), reducao.path("correlacao").asText());
        assertEquals(admissao("compra").toString(), reducao.path("causacao").asText());
        assertEquals(3, parcial.get(1).path("versao").asInt(), "negócio com perna OCO é v3");
        var total = processar(s, comando("compra2", "LIMIT", "COMPRA", 3, "12.00", null));
        JsonNode cancelada = total.stream().filter(e -> e.path("dados").path("motivo").asText().equals("CANCELADA_OCO"))
                .findFirst().orElseThrow();
        assertEquals(3, cancelada.path("versao").asInt());
        assertEquals(id("sl:par").toString(), cancelada.path("dados").path("ordem").asText());
        assertEquals(0, cancelada.path("dados").path("executada").asLong());
        assertEquals(3, cancelada.path("dados").path("cancelada").asLong());
    }

    @Test
    void replayDoParReproduzBytes() throws Exception {
        List<byte[]> comandos = List.of(venda("par", 4, "12.00", "9.00"),
                comando("compra", "LIMIT", "COMPRA", 1, "12.00", null),
                comando("vendaGatilho", "LIMIT", "VENDA", 1, "9.00", null),
                comando("compraGatilho", "LIMIT", "COMPRA", 1, "9.00", null));
        var a = new SessaoMatching();
        var b = new SessaoMatching();
        for (var c : comandos) {
            var x = a.processar(EnvelopeMatching.comando(c, "ALFA3"));
            var y = b.processar(EnvelopeMatching.comando(c, "ALFA3"));
            assertEquals(x.size(), y.size());
            for (int i = 0; i < x.size(); i++) assertArrayEquals(x.get(i), y.get(i));
        }
        assertTrue(a.processar(EnvelopeMatching.comando(comandos.getFirst(), "ALFA3")).isEmpty(), "reentrega do par");
    }

    @Test
    void parDeCompraComStopLimitEValidacoesDaFronteira() throws Exception {
        byte[] valido = par("c", "COMPRA", 2, "8.00", "STOP_LIMIT", "11.20", "11.00", "11.20");
        assertDoesNotThrow(() -> EnvelopeMatching.comando(valido, "ALFA3"));
        String original = new String(valido, StandardCharsets.UTF_8);
        String sl = id("sl:c").toString(), tp = id("tp:c").toString();
        for (String invalido : List.of(
                original.replace("\"versao\":3", "\"versao\":4"),
                original.replace("\"precoLimite\":\"8.00\"", "\"precoLimite\":\"11.00\""),
                original.replace("\"precoLimite\":\"11.20\"", "\"precoLimite\":\"10.90\""),
                original.replace("\"precoLimite\":\"11.20\"", "\"precoLimite\":\"11.30\""),
                original.replace("\"precoMaximo\":\"11.20\"", "\"precoMaximo\":null"),
                original.replace("\"quantidade\":2,\"precoLimite\":\"11.20\"", "\"quantidade\":3,\"precoLimite\":\"11.20\""),
                original.replace("\"papel\":\"TAKE_PROFIT\"", "\"papel\":\"STOP_LOSS\""),
                original.replace("\"tipo\":\"STOP_LIMIT\"", "\"tipo\":\"LIMIT\""),
                original.replace("\"validade\":\"DIA\",\"quantidade\":2,\"precoLimite\":\"8.00\"",
                        "\"validade\":\"IOC\",\"quantidade\":2,\"precoLimite\":\"8.00\""),
                original.replace(sl, tp),
                original.replace("\"precoGatilho\":\"11.00\"", "\"precoGatilho\":\"11.00\",\"extra\":0"),
                original.replace("\"precoMaximo\":\"11.20\"", "\"precoMaximo\":\"11.20\",\"ordem\":\"" + tp + "\""))) {
            assertThrows(IllegalArgumentException.class,
                    () -> EnvelopeMatching.comando(invalido.getBytes(StandardCharsets.UTF_8), "ALFA3"), invalido);
        }
        ObjectNode tresPernas = (ObjectNode) JSON.readTree(valido);
        ((ArrayNode) tresPernas.get("dados").get("pernas")).add(tresPernas.get("dados").get("pernas").get(1).deepCopy());
        assertThrows(IllegalArgumentException.class,
                () -> EnvelopeMatching.comando(JSON.writeValueAsBytes(tresPernas), "ALFA3"));
    }

    @Test
    void sessaoRecusaPernaReaproveitadaComoOrdemSimplesEIntencaoDoPar() throws Exception {
        var s = new SessaoMatching();
        processar(s, venda("par", 4, "12.00", "9.00"));
        ObjectNode simples = (ObjectNode) JSON.readTree(comando("outra", "LIMIT", "VENDA", 1, "12.00", null));
        ((ObjectNode) simples.get("dados")).put("ordem", id("sl:par").toString());
        assertThrows(IllegalArgumentException.class,
                () -> s.processar(EnvelopeMatching.comando(JSON.writeValueAsBytes(simples), "ALFA3")));
        byte[] divergente = venda("par", 4, "12.50", "9.00");
        assertThrows(IllegalArgumentException.class, () -> s.processar(EnvelopeMatching.comando(divergente, "ALFA3")));
    }
}
