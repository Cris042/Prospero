package br.prospero.me.matching;

import br.prospero.me.matching.helper.EnvelopeMatching;
import br.prospero.me.matching.helper.SessaoMatching;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StopsIntegracaoTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    static UUID id(String s) { return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8)); }
    static UUID admissao(String s) { return id("admissao:" + id("intencao:" + s)); }
    static byte[] comando(String nome, String tipo, String lado, int q, String limite, String gatilho) throws Exception {
        ObjectNode r = (ObjectNode) JSON.readTree(EnvelopeMatchingTest.comando(id(nome), id("intencao:" + nome), lado,
                limite == null ? "10.00" : limite, "2026-10-18"));
        ObjectNode d = (ObjectNode) r.get("dados");
        d.put("tipo", tipo); d.put("quantidade", q); d.put("precoLimite", limite);
        d.put("precoMaximo", lado.equals("COMPRA") ? (tipo.equals("STOP") ? "11.00" : limite) : null);
        if (tipo.startsWith("STOP")) { r.put("versao", 2); d.put("precoGatilho", gatilho); }
        if (tipo.equals("MARKET")) { d.put("validade", "IOC"); d.put("precoMaximo", lado.equals("COMPRA") ? "11.00" : null); }
        return JSON.writeValueAsBytes(r);
    }
    static List<JsonNode> processar(SessaoMatching s, byte[] c) throws Exception {
        List<JsonNode> r = new ArrayList<>();
        for (byte[] b : s.processar(EnvelopeMatching.comando(c, "ALFA3"))) r.add(JSON.readTree(b));
        return r;
    }
    @Test void stopV2PendentePublicaIdentidadeEGatilhoSemUsarReferencia() throws Exception {
        var eventos = processar(new SessaoMatching(), comando("stop", "STOP", "COMPRA", 2, null, "10.00"));
        assertEquals(List.of("ORDEM_ACEITA", "STOP_PENDENTE"), eventos.stream().map(e -> e.path("tipo").asText()).toList());
        for (var e : eventos) {
            assertEquals(2, e.path("versao").asInt());
            assertEquals(admissao("stop").toString(), e.path("correlacao").asText());
            assertEquals(admissao("stop").toString(), e.path("causacao").asText());
            assertEquals(id("stop").toString(), e.path("dados").path("ordem").asText());
        }
        assertEquals("10.00", eventos.getLast().path("dados").path("precoGatilho").asText());
    }
    @Test void stopLimitV2EValidacoesDaFronteira() throws Exception {
        byte[] valido = comando("sl", "STOP_LIMIT", "COMPRA", 2, "10.00", "10.00");
        assertDoesNotThrow(() -> EnvelopeMatching.comando(valido, "ALFA3"));
        String original = new String(valido, StandardCharsets.UTF_8);
        for (String invalido : List.of(original.replace("\"versao\":2", "\"versao\":1"),
                original.replace("\"validade\":\"DIA\"", "\"validade\":\"IOC\""),
                original.replace("\"precoGatilho\":\"10.00\"", "\"precoGatilho\":null"),
                original.replace("\"precoGatilho\":\"10.00\"", "\"precoGatilho\":\"10.001\""),
                original.replace("\"precoGatilho\":\"10.00\"", "\"precoGatilho\":\"10.01\""),
                original.replace("\"precoGatilho\":\"10.00\"", "\"precoGatilho\":\"10.00\",\"extra\":0"))) {
            assertThrows(IllegalArgumentException.class, () -> EnvelopeMatching.comando(invalido.getBytes(StandardCharsets.UTF_8), "ALFA3"));
        }
    }
    @Test void cascataSeparaRaizDeAgressoraEMantemReplayByteAByte() throws Exception {
        List<byte[]> comandos = List.of(comando("sl", "STOP_LIMIT", "COMPRA", 2, "10.00", "10.00"),
                comando("venda", "LIMIT", "VENDA", 4, "10.00", null),
                comando("raiz", "LIMIT", "COMPRA", 1, "10.00", null));
        var motor = new SessaoMatching(); var replay = new SessaoMatching();
        for (var c : comandos) {
            var a = motor.processar(EnvelopeMatching.comando(c, "ALFA3"));
            var b = replay.processar(EnvelopeMatching.comando(c, "ALFA3"));
            assertEquals(a.size(), b.size());
            for (int i = 0; i < a.size(); i++) assertArrayEquals(a.get(i), b.get(i));
        }
        var fresh = new SessaoMatching(); processar(fresh, comandos.get(0)); processar(fresh, comandos.get(1));
        var eventos = processar(fresh, comandos.getLast());
        var negocios = eventos.stream().filter(e -> e.path("tipo").asText().equals("NEGOCIO")).toList();
        assertEquals(2, negocios.size());
        var stop = negocios.getLast();
        assertEquals(2, stop.path("versao").asInt());
        assertEquals(admissao("raiz").toString(), stop.path("causacao").asText());
        assertEquals(admissao("sl").toString(), stop.path("correlacao").asText());
        assertEquals(id("sl").toString(), stop.path("dados").path("ordemCompra").asText());
        assertEquals("2026-10-18T12:00:00Z", stop.path("ocorridoEm").asText());
        for (int i = 0; i < eventos.size(); i++) assertEquals(id("matching:" + admissao("raiz") + ":" + i).toString(), eventos.get(i).path("id").asText());
        assertTrue(fresh.processar(EnvelopeMatching.comando(comandos.getLast(), "ALFA3")).isEmpty());
    }
    @Test void stopLimitPassivaGeraV2MesmoSobRaizMarketLimitV1() throws Exception {
        var s = new SessaoMatching();
        processar(s, comando("seed-v", "LIMIT", "VENDA", 1, "10.00", null));
        processar(s, comando("seed-c", "LIMIT", "COMPRA", 1, "10.00", null));
        var ativacao = processar(s, comando("passiva", "STOP_LIMIT", "COMPRA", 2, "10.00", "10.00"));
        assertTrue(ativacao.stream().anyMatch(e -> e.path("tipo").asText().equals("STOP_ACIONADO")));
        var eventos = processar(s, comando("venda-raiz", "LIMIT", "VENDA", 2, "10.00", null));
        var negocio = eventos.stream().filter(e -> e.path("tipo").asText().equals("NEGOCIO")).findFirst().orElseThrow();
        assertEquals(2, negocio.path("versao").asInt());
        assertEquals(admissao("venda-raiz").toString(), negocio.path("causacao").asText());
        assertEquals(admissao("venda-raiz").toString(), negocio.path("correlacao").asText());
        assertEquals(id("passiva").toString(), negocio.path("dados").path("ordemCompra").asText());
        var terminal = eventos.stream().filter(e -> e.path("tipo").asText().equals("ORDEM_ENCERRADA")
                && e.path("dados").path("ordem").asText().equals(id("passiva").toString())).findFirst().orElseThrow();
        assertEquals(2, terminal.path("versao").asInt());
        assertEquals(admissao("passiva").toString(), terminal.path("correlacao").asText());
        assertEquals(1, eventos.getFirst().path("versao").asInt(), "aceite da raiz normal continua legado");
        assertFalse(eventos.getFirst().has("causacao"));
    }
    @Test void excesso32TemTerminalV2SemAcionamentoEExcesso500TemRejeicaoV2() throws Exception {
        var s = new SessaoMatching();
        for (int i=0;i<33;i++) processar(s,comando("s"+i,"STOP","COMPRA",1,null,"10.00"));
        processar(s,comando("liquidez","LIMIT","VENDA",40,"10.00",null));
        var eventos=processar(s,comando("raiz32","LIMIT","COMPRA",1,"10.00",null));
        assertEquals(32,eventos.stream().filter(e->e.path("tipo").asText().equals("STOP_ACIONADO")).count());
        var excesso=eventos.stream().filter(e->e.path("dados").path("motivo").asText().equals("LIMITE_CASCATA")).findFirst().orElseThrow();
        assertEquals(2,excesso.path("versao").asInt());
        assertEquals(id("s32").toString(),excesso.path("dados").path("ordem").asText());
        assertEquals(admissao("s32").toString(),excesso.path("correlacao").asText());
        assertEquals(0,excesso.path("dados").path("executada").asInt());
        assertEquals(1,excesso.path("dados").path("cancelada").asInt());
        var lotado=new SessaoMatching();
        for(int i=0;i<500;i++) processar(lotado,comando("p"+i,"STOP","COMPRA",1,null,"10.00"));
        var rejeicao=processar(lotado,comando("p500","STOP","COMPRA",1,null,"10.00"));
        assertEquals(1,rejeicao.size()); assertEquals(2,rejeicao.getFirst().path("versao").asInt());
        assertEquals("LIMITE_STOPS_PENDENTES",rejeicao.getFirst().path("dados").path("motivo").asText());
    }
    @Test void publicacaoParcialDaCascataNaoAvancaCheckpointEReplayConservaBytes() throws Exception {
        List<byte[]> log=List.of(comando("stop-parcial","STOP","COMPRA",2,null,"10.00"),
                comando("oferta-parcial","LIMIT","VENDA",3,"10.00",null),
                comando("gatilho-parcial","LIMIT","COMPRA",1,"10.00",null));
        var s=new SessaoMatching(); var replay=new SessaoMatching();
        for(int i=0;i<2;i++){ processar(s,log.get(i)); processar(replay,log.get(i)); }
        var eventos=s.processar(EnvelopeMatching.comando(log.getLast(),"ALFA3"));
        var acks=new java.util.concurrent.atomic.AtomicInteger();
        var checkpoint=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(java.io.IOException.class,()->br.prospero.me.matching.kafka.PublicacaoMatching.publicar(eventos,e->{
            if(acks.incrementAndGet()==5) throw new java.io.IOException("falha apos acionamento");
        },checkpoint::incrementAndGet));
        assertEquals(0,checkpoint.get());
        var repetidos=replay.processar(EnvelopeMatching.comando(log.getLast(),"ALFA3"));
        assertEquals(eventos.size(),repetidos.size());
        for(int i=0;i<eventos.size();i++) assertArrayEquals(eventos.get(i),repetidos.get(i));
        br.prospero.me.matching.kafka.PublicacaoMatching.publicar(repetidos,e->{},checkpoint::incrementAndGet);
        assertEquals(1,checkpoint.get());
    }

}
