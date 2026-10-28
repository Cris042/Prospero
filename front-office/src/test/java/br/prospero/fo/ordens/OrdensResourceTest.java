package br.prospero.fo.ordens;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class OrdensResourceTest {
    private static final String CHAVE = "chave-sintetica-de-teste-com-mais-de-32-bytes";

    @Inject
    RepositorioOrdensMemoria repositorio;

    @Inject
    FonteUltimoPrecoControlada ultimos;

    @BeforeEach
    void limpar() {
        repositorio.limparTudo();
        ultimos.limpar();
    }

    private static String token(String conta, String... grupos) {
        return Jwt.issuer("prospero-local").audience("prospero-front-office").subject(conta).groups(Set.of(grupos))
                .expiresIn(300).signWithSecret(CHAVE);
    }

    private static String conta(String conta) {
        return token(conta, "ordens:enviar", "ordens:leitura");
    }

    private static Map<String, Object> limit(String preco) {
        Map<String, Object> corpo = new HashMap<>(Map.of("ticker", "ALFA3", "lado", "COMPRA", "tipo", "LIMIT",
                "validade", "DIA", "quantidade", 100));
        corpo.put("precoLimite", preco);
        return corpo;
    }

    private static Response enviar(String token, String chave, Object corpo) {
        return given().auth().oauth2(token).header("X-Idempotency-Key", chave).contentType(ContentType.JSON).body(corpo)
                .when().post("/api/v1/ordens");
    }

    @Test
    void registraConsultaENaoPrometeExecucao() {
        Response r = enviar(conta("CONTA-002"), "envio-0001", limit("10.1"));
        r.then().statusCode(202).header("Cache-Control", "no-store").header("X-Idempotencia-Repetida", "false")
                .header("Location", startsWith("/api/v1/ordens/"))
                .body("estado", equalTo("RECEBIDA")).body("precoLimite", equalTo("10.10"))
                .body("precoGatilho", nullValue()).body("grupoOco", nullValue());
        String id = r.path("id");
        given().auth().oauth2(conta("CONTA-002")).when().get("/api/v1/ordens/" + id).then()
                .statusCode(200).body("id", equalTo(id)).body("tipo", equalTo("LIMIT")).body("validade", equalTo("DIA"));
    }

    @Test
    void idempotenciaRepeteDivergeEIsolaContas() {
        String id = enviar(conta("CONTA-002"), "envio-0002", limit("10.00")).then().statusCode(202).extract().path("id");
        enviar(conta("CONTA-002"), "envio-0002", limit("10.0")).then().statusCode(202)
                .header("X-Idempotencia-Repetida", "true").body("id", equalTo(id));
        enviar(conta("CONTA-002"), "envio-0002", limit("10.05")).then().statusCode(409)
                .contentType("application/problem+json").body("codigo", equalTo("IDEMPOTENCIA_CONFLITO"));
        String outra = enviar(conta("CONTA-003"), "envio-0002", limit("10.00")).then().statusCode(202).extract().path("id");
        org.junit.jupiter.api.Assertions.assertNotEquals(id, outra);
        given().auth().oauth2(conta("CONTA-002")).when().get("/api/v1/ordens").then().body("ordens", hasSize(1));
    }

    @Test
    void enviosConcorrentesComMesmaChaveCriamUmaOrdem() throws Exception {
        String token = conta("CONTA-002");
        Callable<String> tarefa = () -> enviar(token, "envio-concorrente", limit("10.00")).then().statusCode(202)
                .extract().path("id");
        var executor = Executors.newFixedThreadPool(8);
        List<Future<String>> futuros = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futuros.add(executor.submit(tarefa));
        }
        Set<String> ids = new java.util.HashSet<>();
        for (Future<String> f : futuros) {
            ids.add(f.get());
        }
        executor.shutdown();
        assertEquals(1, ids.size());
        given().auth().oauth2(token).when().get("/api/v1/ordens").then().body("ordens", hasSize(1));
    }

    @Test
    void ordemDeOutraContaEInexistenteSaoIndistinguiveis() {
        String id = enviar(conta("CONTA-002"), "envio-0003", limit("10.00")).then().extract().path("id");
        for (String alvo : new String[]{id, UUID.randomUUID().toString(), "nao-e-uuid"}) {
            given().auth().oauth2(conta("CONTA-003")).when().get("/api/v1/ordens/" + alvo).then()
                    .statusCode(404).body("codigo", equalTo("ORDEM_NAO_ENCONTRADA"));
        }
        given().auth().oauth2(conta("CONTA-003")).when().get("/api/v1/ordens").then().body("ordens", hasSize(0));
    }

    @Test
    void combinacoesEEstruturaInvalidasPelaApi() {
        String t = conta("CONTA-002");
        Map<String, Object> marketDia = new HashMap<>(Map.of("ticker", "ALFA3", "lado", "VENDA", "tipo", "MARKET",
                "validade", "DIA", "quantidade", 10));
        enviar(t, "envio-0101", marketDia).then().statusCode(422).body("codigo", equalTo("COMBINACAO_INVALIDA"));
        Map<String, Object> stopLimitSemLimite = new HashMap<>(Map.of("ticker", "ALFA3", "lado", "VENDA",
                "tipo", "STOP_LIMIT", "validade", "DIA", "quantidade", 10, "precoGatilho", "9.50"));
        enviar(t, "envio-0102", stopLimitSemLimite).then().statusCode(422).body("codigo", equalTo("CAMPO_EXIGIDO_PELO_TIPO"));
        Map<String, Object> desconhecido = limit("10.00");
        desconhecido.put("ticker", "ZETA3");
        enviar(t, "envio-0103", desconhecido).then().statusCode(422).body("codigo", equalTo("ATIVO_DESCONHECIDO"));
        Map<String, Object> comConta = limit("10.00");
        comConta.put("conta", "CONTA-003");
        enviar(t, "envio-0104", comConta).then().statusCode(400).body("codigo", equalTo("JSON_INVALIDO"));
        Map<String, Object> fracionaria = limit("10.00");
        fracionaria.put("quantidade", 1.5);
        enviar(t, "envio-0105", fracionaria).then().statusCode(400).body("codigo", equalTo("JSON_INVALIDO"));
        Map<String, Object> semTipo = limit("10.00");
        semTipo.remove("tipo");
        enviar(t, "envio-0106", semTipo).then().statusCode(400).body("codigo", equalTo("CORPO_INVALIDO"));
        enviar(t, "envio-0107", limit("1e3")).then().statusCode(422).body("codigo", equalTo("PRECO_INVALIDO"));
        enviar(t, "envio-0108", "{\"ticker\":\"ALFA3\",\"ticker\":\"BETA3\",\"lado\":\"COMPRA\",\"tipo\":\"LIMIT\","
                + "\"validade\":\"DIA\",\"quantidade\":1,\"precoLimite\":\"10.00\"}").then().statusCode(400)
                .body("codigo", equalTo("JSON_INVALIDO"));
        Map<String, Object> tickerLongo = limit("10.00");
        tickerLongo.put("ticker", "A".repeat(9));
        enviar(t, "envio-0109", tickerLongo).then().statusCode(400).body("codigo", equalTo("CORPO_INVALIDO"));
        for (String chave : new String[]{null, "curta", "com espaço!", "x".repeat(65)}) {
            var pedido = given().auth().oauth2(t).contentType(ContentType.JSON).body(limit("10.00"));
            if (chave != null) {
                pedido.header("X-Idempotency-Key", chave);
            }
            pedido.when().post("/api/v1/ordens").then().statusCode(400).body("codigo", equalTo("IDEMPOTENCIA_AUSENTE"));
        }
        given().auth().oauth2(t).when().get("/api/v1/ordens").then().body("ordens", hasSize(0));
    }

    @Test
    void ocoRegistraDuasPernasAtomicamente() {
        Map<String, Object> oco = Map.of("ticker", "BETA3", "lado", "VENDA", "quantidade", 50, "validade", "DIA",
                "takeProfit", Map.of("precoLimite", "21.00"),
                "stopLoss", Map.of("tipo", "STOP_LIMIT", "precoGatilho", "19.00", "precoLimite", "18.90"));
        Response r = given().auth().oauth2(conta("CONTA-002")).header("X-Idempotency-Key", "oco-00001")
                .contentType(ContentType.JSON).body(oco).when().post("/api/v1/ordens-oco");
        r.then().statusCode(202).header("Location", startsWith("/api/v1/ordens-oco/"))
                .body("ordens", hasSize(2)).body("ordens[0].papelOco", equalTo("TAKE_PROFIT"))
                .body("ordens[0].tipo", equalTo("LIMIT")).body("ordens[1].papelOco", equalTo("STOP_LOSS"))
                .body("ordens[1].precoGatilho", equalTo("19.00"));
        String grupo = r.path("grupoOco");
        given().auth().oauth2(conta("CONTA-002")).when().get("/api/v1/ordens-oco/" + grupo).then()
                .statusCode(200).body("ordens", hasSize(2));
        given().auth().oauth2(conta("CONTA-003")).when().get("/api/v1/ordens-oco/" + grupo).then().statusCode(404);
        Map<String, Object> incoerente = new HashMap<>(oco);
        incoerente.put("takeProfit", Map.of("precoLimite", "18.00"));
        given().auth().oauth2(conta("CONTA-002")).header("X-Idempotency-Key", "oco-00002").contentType(ContentType.JSON)
                .body(incoerente).when().post("/api/v1/ordens-oco").then().statusCode(422)
                .body("codigo", equalTo("OCO_PRECOS_INCOERENTES"));
        Map<String, Object> ioc = new HashMap<>(oco);
        ioc.put("validade", "IOC");
        given().auth().oauth2(conta("CONTA-002")).header("X-Idempotency-Key", "oco-00003").contentType(ContentType.JSON)
                .body(ioc).when().post("/api/v1/ordens-oco").then().statusCode(422)
                .body("codigo", equalTo("COMBINACAO_INVALIDA"));
        given().auth().oauth2(conta("CONTA-002")).when().get("/api/v1/ordens").then().body("ordens", hasSize(2));
    }

    @Test
    void paginacaoPorCursorELimites() {
        String t = conta("CONTA-002");
        for (int i = 0; i < 5; i++) {
            enviar(t, "pagina-000" + i, limit("10.0" + i)).then().statusCode(202);
        }
        Response primeira = given().auth().oauth2(t).queryParam("limite", "2").when().get("/api/v1/ordens");
        primeira.then().statusCode(200).body("ordens", hasSize(2));
        String cursor = primeira.path("proximoCursor");
        List<String> vistos = new ArrayList<>(primeira.path("ordens.id"));
        while (cursor != null) {
            Response proxima = given().auth().oauth2(t).queryParam("limite", "2").queryParam("cursor", cursor)
                    .when().get("/api/v1/ordens");
            vistos.addAll(proxima.path("ordens.id"));
            cursor = proxima.path("proximoCursor");
        }
        assertEquals(5, Set.copyOf(vistos).size());
        given().auth().oauth2(t).queryParam("limite", "101").when().get("/api/v1/ordens").then()
                .statusCode(400).body("codigo", equalTo("LIMITE_PAGINA_INVALIDO"));
        given().auth().oauth2(t).queryParam("cursor", "lixo").when().get("/api/v1/ordens").then()
                .statusCode(400).body("codigo", equalTo("CURSOR_INVALIDO"));
    }

    @Test
    void limiteDeOrdensPorSessao() {
        repositorio.maximoSessao(2);
        String t = conta("CONTA-002");
        enviar(t, "limite-0001", limit("10.00")).then().statusCode(202);
        enviar(t, "limite-0002", limit("10.01")).then().statusCode(202);
        enviar(t, "limite-0003", limit("10.02")).then().statusCode(429).body("codigo", equalTo("LIMITE_ORDENS"));
    }

    @Test
    void autorizacaoPorEscopoEConta() {
        enviar(token("CONTA-002", "ordens:leitura"), "auth-00001", limit("10.00")).then().statusCode(403);
        enviar(token("CONTA-002", "mercado:leitura"), "auth-00002", limit("10.00")).then().statusCode(403);
        enviar(conta("CONTA-999"), "auth-00003", limit("10.00")).then().statusCode(403)
                .body("codigo", equalTo("CONTA_DESCONHECIDA"));
        // RF05d: o robô formador envia e lê pela própria conta, com o mesmo contrato das demais.
        enviar(conta("CONTA-004"), "auth-00006", limit("10.00")).then().statusCode(202).body("estado", equalTo("RECEBIDA"));
        given().auth().oauth2(conta("CONTA-004")).when().get("/api/v1/ordens").then().statusCode(200)
                .body("ordens.size()", equalTo(1));
        given().header("X-Idempotency-Key", "auth-00004").contentType(ContentType.JSON).body(limit("10.00"))
                .when().post("/api/v1/ordens").then().statusCode(401);
        given().auth().oauth2(token("CONTA-002", "ordens:enviar")).when().get("/api/v1/ordens").then().statusCode(403);
        given().auth().oauth2(conta("CONTA-999")).when().get("/api/v1/ordens").then().statusCode(403)
                .body("codigo", equalTo("CONTA_DESCONHECIDA"));
        given().auth().oauth2(conta("CONTA-999")).when().get("/api/v1/ordens/" + UUID.randomUUID()).then().statusCode(403);
        given().auth().oauth2(conta("CONTA-002")).header("X-Idempotency-Key", "auth-00005").contentType(ContentType.TEXT)
                .body("x").when().post("/api/v1/ordens").then().statusCode(415);
    }

    @Test
    void contaDeMedicao005EnviaELeOrdensComoAsDemais() {
        // V01 (RF05d/RNF04): a CONTA-005 é conta de negócio do FO; conta inexistente continua recusada.
        enviar(conta("CONTA-005"), "medicao-00001", limit("10.00")).then().statusCode(202).body("estado", equalTo("RECEBIDA"));
        given().auth().oauth2(conta("CONTA-005")).when().get("/api/v1/ordens").then().statusCode(200)
                .body("ordens.size()", equalTo(1));
        enviar(conta("CONTA-999"), "medicao-00002", limit("10.00")).then().statusCode(403)
                .body("codigo", equalTo("CONTA_DESCONHECIDA"));
    }

    private static Map<String, Object> ordem(String ticker, String lado, String tipo, String validade, long quantidade,
                                             String limite, String gatilho) {
        Map<String, Object> corpo = new HashMap<>(Map.of("ticker", ticker, "lado", lado, "tipo", tipo,
                "validade", validade, "quantidade", quantidade));
        if (limite != null) {
            corpo.put("precoLimite", limite);
        }
        if (gatilho != null) {
            corpo.put("precoGatilho", gatilho);
        }
        return corpo;
    }

    @Test
    void tunelNasFronteirasComDetalhes() {
        String t = conta("CONTA-002");
        enviar(t, "tunel-0001", limit("9.00")).then().statusCode(202)
                .body("precoBaseTunel", equalTo("10.00")).body("origemBase", equalTo("REFERENCIA"));
        enviar(t, "tunel-0002", limit("11.00")).then().statusCode(202);
        enviar(t, "tunel-0003", limit("8.99")).then().statusCode(422).body("codigo", equalTo("PRECO_FORA_DO_TUNEL"))
                .body("detalhes.minimo", equalTo("9.00")).body("detalhes.maximo", equalTo("11.00"))
                .body("detalhes.base", equalTo("10.00")).body("detalhes.origemBase", equalTo("REFERENCIA"));
        enviar(t, "tunel-0004", limit("11.01")).then().statusCode(422).body("codigo", equalTo("PRECO_FORA_DO_TUNEL"));
        enviar(t, "tunel-0005", ordem("ALFA3", "VENDA", "STOP", "DIA", 10, null, "8.99")).then().statusCode(422)
                .body("codigo", equalTo("PRECO_FORA_DO_TUNEL"));
        enviar(t, "tunel-0006", ordem("ALFA3", "VENDA", "STOP_LIMIT", "DIA", 10, "8.99", "9.00")).then().statusCode(422)
                .body("codigo", equalTo("PRECO_FORA_DO_TUNEL"));
        enviar(t, "tunel-0007", ordem("ALFA3", "COMPRA", "MARKET", "IOC", 10, null, null)).then().statusCode(202)
                .body("precoBaseTunel", nullValue()).body("origemBase", nullValue());
    }

    @Test
    void loteTickEStopLimitNoCatalogo() {
        String t = conta("CONTA-002");
        enviar(t, "omega-0001", ordem("OMEGA3", "COMPRA", "LIMIT", "DIA", 150, "50.05", null)).then().statusCode(422)
                .body("codigo", equalTo("QUANTIDADE_FORA_DO_LOTE")).body("detalhes.lote", equalTo("100"));
        enviar(t, "omega-0002", ordem("OMEGA3", "COMPRA", "LIMIT", "DIA", 100, "50.03", null)).then().statusCode(422)
                .body("codigo", equalTo("PRECO_FORA_DO_TICK")).body("detalhes.tick", equalTo("0.05"));
        enviar(t, "omega-0003", ordem("OMEGA3", "COMPRA", "LIMIT", "DIA", 200, "50.05", null)).then().statusCode(202);
        enviar(t, "omega-0004", ordem("ALFA3", "VENDA", "STOP_LIMIT", "DIA", 10, "9.60", "9.50")).then().statusCode(422)
                .body("codigo", equalTo("STOP_LIMIT_INCOERENTE"));
        enviar(t, "omega-0005", ordem("ALFA3", "COMPRA", "STOP_LIMIT", "DIA", 10, "9.55", "9.50")).then().statusCode(202);
    }

    @Test
    void ultimoDaSessaoMudaABaseERepeticaoDevolveOriginal() {
        String t = conta("CONTA-002");
        ultimos.definir("ALFA3", "10.50");
        enviar(t, "base-00001", limit("11.55")).then().statusCode(202)
                .body("precoBaseTunel", equalTo("10.50")).body("origemBase", equalTo("ULTIMO"));
        ultimos.definir("ALFA3", "9.00");
        // Mesmo pedido aceito antes continua 202 repetido, mesmo fora do túnel da nova base.
        enviar(t, "base-00001", limit("11.55")).then().statusCode(202).header("X-Idempotencia-Repetida", "true")
                .body("precoBaseTunel", equalTo("10.50"));
        enviar(t, "base-00002", limit("11.55")).then().statusCode(422).body("detalhes.maximo", equalTo("9.90"))
                .body("detalhes.origemBase", equalTo("ULTIMO"));
    }

    @Test
    void recusaNaoConsomeChaveNemGravaOco() {
        String t = conta("CONTA-002");
        enviar(t, "recusa-001", limit("12.00")).then().statusCode(422);
        enviar(t, "recusa-001", limit("10.50")).then().statusCode(202).header("X-Idempotencia-Repetida", "false");
        Map<String, Object> oco = Map.of("ticker", "ALFA3", "lado", "VENDA", "quantidade", 10, "validade", "DIA",
                "takeProfit", Map.of("precoLimite", "11.50"), "stopLoss", Map.of("tipo", "STOP", "precoGatilho", "9.50"));
        given().auth().oauth2(t).header("X-Idempotency-Key", "recusa-oco1").contentType(ContentType.JSON).body(oco)
                .when().post("/api/v1/ordens-oco").then().statusCode(422).body("codigo", equalTo("PRECO_FORA_DO_TUNEL"));
        given().auth().oauth2(t).when().get("/api/v1/ordens").then().body("ordens", hasSize(1));
    }

    @Test
    void precedenciaDasRegrasEChaveAntesDoTunel() {
        String t = conta("CONTA-002");
        // Lote antes de tick antes de túnel.
        enviar(t, "prec-00001", ordem("OMEGA3", "COMPRA", "LIMIT", "DIA", 150, "60.03", null)).then().statusCode(422)
                .body("codigo", equalTo("QUANTIDADE_FORA_DO_LOTE"));
        enviar(t, "prec-00002", ordem("OMEGA3", "COMPRA", "LIMIT", "DIA", 100, "60.03", null)).then().statusCode(422)
                .body("codigo", equalTo("PRECO_FORA_DO_TICK"));
        enviar(t, "prec-00003", ordem("OMEGA3", "COMPRA", "LIMIT", "DIA", 100, "60.05", null)).then().statusCode(422)
                .body("codigo", equalTo("PRECO_FORA_DO_TUNEL"));
        // Chave já aceita: corpo que violaria o túnel recebe 409 (chave consultada antes do túnel).
        enviar(t, "prec-00004", limit("10.00")).then().statusCode(202);
        enviar(t, "prec-00004", limit("12.00")).then().statusCode(409).body("codigo", equalTo("IDEMPOTENCIA_CONFLITO"));
        // Recusas não consomem a cota: com teto 1 já usado pela ordem acima, só novas aceitas contam.
        repositorio.maximoSessao(2);
        enviar(t, "prec-00005", limit("12.00")).then().statusCode(422);
        enviar(t, "prec-00006", limit("10.10")).then().statusCode(202);
        enviar(t, "prec-00007", limit("10.20")).then().statusCode(429);
    }
}
