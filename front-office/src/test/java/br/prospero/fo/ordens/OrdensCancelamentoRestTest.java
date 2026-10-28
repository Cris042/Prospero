package br.prospero.fo.ordens;

import br.prospero.fo.ordens.dominio.EstadoOrdem;
import br.prospero.fo.ordens.dominio.ResultadoCancelamento;
import br.prospero.fo.ordens.dominio.ResultadoRisco;
import br.prospero.fo.ordens.actor.AplicarCancelamentoActor;
import br.prospero.fo.ordens.helper.EnvelopeRisco;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RF10: PUT/GET canônicos do cancelamento manual, sobre o adaptador em memória (a semântica JPA tem IT próprio). */
@QuarkusTest
class OrdensCancelamentoRestTest {
    private static final String CHAVE = "chave-sintetica-de-teste-com-mais-de-32-bytes";
    @Inject RepositorioOrdensMemoria ordens;
    @Inject RepositorioCancelamentoMemoria cancelamentos;
    @Inject AplicarCancelamentoActor aplicar;
    @Inject FonteUltimoPrecoControlada ultimos;

    @BeforeEach
    void limpar() {
        ordens.limparTudo();
        cancelamentos.limpar();
        ultimos.limpar();
    }

    private static String token(String conta, String... escopos) {
        return Jwt.issuer("prospero-local").audience("prospero-front-office").subject(conta).groups(Set.of(escopos))
                .expiresIn(300).signWithSecret(CHAVE);
    }

    private static String completo(String conta) {
        return token(conta, "ordens:enviar", "ordens:leitura");
    }

    private String ordem(String conta, String chave) {
        return given().auth().oauth2(completo(conta)).header("X-Idempotency-Key", chave).contentType(ContentType.JSON)
                .body(Map.of("ticker", "ALFA3", "lado", "COMPRA", "tipo", "LIMIT", "validade", "DIA", "quantidade", 100,
                        "precoLimite", "10.10"))
                .post("/api/v1/ordens").then().statusCode(202).extract().path("id");
    }

    private void decidirRisco(String id, EstadoOrdem estado) {
        var ordem = ordens.buscar("CONTA-002", UUID.fromString(id)).orElseThrow();
        ordens.aplicarRisco(new ResultadoRisco(UUID.randomUUID(), ordem.grupoEnvio(), "CONTA-002", estado,
                estado == EstadoOrdem.REJEITADA ? br.prospero.fo.ordens.dominio.MotivoRejeicao.SALDO_INSUFICIENTE : null));
    }

    @Test
    void ordemSemDecisaoDeRiscoAguardaESemMensagemNaOutbox() {
        String id = ordem("CONTA-002", "cancela-0001");
        int antes = cancelamentos.outbox().size();
        given().auth().oauth2(completo("CONTA-002")).when().put("/api/v1/ordens/" + id + "/cancelamento").then()
                .statusCode(202).header("Cache-Control", "no-store").header("X-Cancelamento-Repetido", "false")
                .body("ordem", equalTo(id)).body("estado", equalTo("AGUARDANDO_RISCO")).body("motivo", nullValue());
        assertEquals(antes, cancelamentos.outbox().size(), "antes do risco nada é emitido");
    }

    @Test
    void decididaEmiteUmaSolicitacaoERepeticaoDevolveAMesmaOperacaoSemNovaOutbox() {
        String id = ordem("CONTA-002", "cancela-0002");
        decidirRisco(id, EstadoOrdem.RESERVADA);
        var rota = "/api/v1/ordens/" + id + "/cancelamento";
        var primeira = given().auth().oauth2(completo("CONTA-002")).contentType(ContentType.JSON).body("{}")
                .when().put(rota).then().statusCode(202).body("estado", equalTo("SOLICITADO")).extract().path("solicitadaEm");
        given().auth().oauth2(completo("CONTA-002")).when().put(rota).then().statusCode(202)
                .header("X-Cancelamento-Repetido", "true").body("estado", equalTo("SOLICITADO"))
                .body("solicitadaEm", equalTo(primeira));
        var emitidas = cancelamentos.outbox();
        assertEquals(1, emitidas.size());
        var msg = emitidas.getFirst();
        assertEquals("risco.comandos.v1", msg.topico());
        assertEquals("CONTA-002", msg.chave());
        assertEquals("CANCELAMENTO_SOLICITADO", msg.tipo());
        assertEquals(EnvelopeRisco.idCancelamentoSolicitado(UUID.fromString(id)), msg.id());
        assertTrue(msg.payload().contains("\"ordem\":\"" + id + "\""));
        given().auth().oauth2(completo("CONTA-002")).when().get(rota).then().statusCode(200)
                .header("Cache-Control", "no-store").body("estado", equalTo("SOLICITADO"));
    }

    @Test
    void resultadoDoMoConfirmaEHeRepetidoSemRegredir() {
        String id = ordem("CONTA-002", "cancela-0003");
        decidirRisco(id, EstadoOrdem.RESERVADA);
        var rota = "/api/v1/ordens/" + id + "/cancelamento";
        given().auth().oauth2(completo("CONTA-002")).put(rota).then().statusCode(202);
        var confirmado = new ResultadoCancelamento(UUID.randomUUID(), UUID.fromString(id), "CONTA-002", true, null,
                java.time.Instant.now(), null, "x");
        assertTrue(aplicar.aplicar(confirmado));
        assertFalse(aplicar.aplicar(confirmado), "repetição sem efeito");
        var recusa = new ResultadoCancelamento(UUID.randomUUID(), UUID.fromString(id), "CONTA-002", false, "SEM_SALDO_VIVO",
                java.time.Instant.now(), null, "y");
        assertFalse(aplicar.aplicar(recusa), "estado final não regride");
        given().auth().oauth2(completo("CONTA-002")).get(rota).then().statusCode(200).body("estado", equalTo("CONFIRMADO"));
    }

    @Test
    void ordemDeOutraContaEInexistenteSaoIndistinguiveis() {
        String id = ordem("CONTA-002", "cancela-0004");
        String inexistente = UUID.randomUUID().toString();
        var outra = given().auth().oauth2(completo("CONTA-003")).put("/api/v1/ordens/" + id + "/cancelamento").then()
                .statusCode(404).contentType("application/problem+json").extract().jsonPath();
        var nenhuma = given().auth().oauth2(completo("CONTA-003")).put("/api/v1/ordens/" + inexistente + "/cancelamento")
                .then().statusCode(404).extract().jsonPath();
        assertEquals((String) nenhuma.get("codigo"), outra.get("codigo"));
        assertEquals((String) nenhuma.get("detail"), outra.get("detail"));
        given().auth().oauth2(completo("CONTA-002")).put("/api/v1/ordens/" + id + "/cancelamento").then().statusCode(202);
        assertEquals(404, given().auth().oauth2(completo("CONTA-003")).get("/api/v1/ordens/" + id + "/cancelamento")
                .statusCode());
        assertEquals(404, given().auth().oauth2(completo("CONTA-002"))
                .get("/api/v1/ordens/" + inexistente + "/cancelamento").statusCode());
        assertEquals(404, given().auth().oauth2(completo("CONTA-002")).put("/api/v1/ordens/nao-uuid/cancelamento").statusCode());
    }

    @Test
    void getSemPedidoDevolve404() {
        String id = ordem("CONTA-002", "cancela-0005");
        given().auth().oauth2(completo("CONTA-002")).get("/api/v1/ordens/" + id + "/cancelamento").then().statusCode(404)
                .body("codigo", equalTo("CANCELAMENTO_NAO_ENCONTRADO"));
    }

    @Test
    void ordemOcoRecebe422SemCriarOperacao() {
        String grupo = given().auth().oauth2(completo("CONTA-002")).header("X-Idempotency-Key", "cancela-oco-1")
                .contentType(ContentType.JSON).body(Map.of("ticker", "ALFA3", "lado", "VENDA", "quantidade", 10,
                        "validade", "DIA", "takeProfit", Map.of("precoLimite", "10.50"),
                        "stopLoss", Map.of("tipo", "STOP", "precoGatilho", "9.50")))
                .post("/api/v1/ordens-oco").then().statusCode(202).extract().path("grupoOco");
        var pernas = given().auth().oauth2(completo("CONTA-002")).get("/api/v1/ordens-oco/" + grupo).then().statusCode(200)
                .extract().jsonPath().getList("ordens.id", String.class);
        assertEquals(2, pernas.size());
        for (String perna : pernas) {
            decidirRisco(perna, EstadoOrdem.RESERVADA);
            given().auth().oauth2(completo("CONTA-002")).put("/api/v1/ordens/" + perna + "/cancelamento").then()
                    .statusCode(422).contentType("application/problem+json")
                    .body("codigo", equalTo("ORDEM_OCO_NAO_CANCELAVEL"));
            given().auth().oauth2(completo("CONTA-002")).get("/api/v1/ordens/" + perna + "/cancelamento").then().statusCode(404);
        }
        assertTrue(cancelamentos.outbox().isEmpty());
    }

    @Test
    void corpoDiferenteDeAusenteOuObjetoVazioEh400() {
        String id = ordem("CONTA-002", "cancela-0006");
        var rota = "/api/v1/ordens/" + id + "/cancelamento";
        for (String corpo : new String[]{"{\"quantidade\":1}", "[]", "null", "{} {}", "{\"a\":1,\"a\":2}", "{", "1"}) {
            given().auth().oauth2(completo("CONTA-002")).contentType(ContentType.JSON).body(corpo).put(rota).then()
                    .statusCode(400).contentType("application/problem+json");
        }
        given().auth().oauth2(completo("CONTA-002")).contentType(ContentType.TEXT).body("{}").put(rota).then().statusCode(400);
        given().auth().oauth2(completo("CONTA-002")).get(rota).then().statusCode(404);
        given().auth().oauth2(completo("CONTA-002")).contentType(ContentType.JSON).body(" {  } ").put(rota).then()
                .statusCode(202);
    }

    @Test
    void escoposEAutenticacao() {
        String id = ordem("CONTA-002", "cancela-0007");
        var rota = "/api/v1/ordens/" + id + "/cancelamento";
        given().put(rota).then().statusCode(401);
        given().auth().oauth2(token("CONTA-002", "ordens:leitura")).put(rota).then().statusCode(403);
        given().auth().oauth2(token("CONTA-002", "ordens:enviar")).get(rota).then().statusCode(403);
        given().auth().oauth2(completo("CONTA-999")).put(rota).then().statusCode(403)
                .body("codigo", equalTo("CONTA_DESCONHECIDA"));
    }

    @Test
    void listagemEOrdemTrazemOResumoDoCancelamento() {
        String id = ordem("CONTA-002", "cancela-0008");
        given().auth().oauth2(completo("CONTA-002")).get("/api/v1/ordens/" + id).then().body("cancelamento", nullValue());
        decidirRisco(id, EstadoOrdem.RESERVADA);
        given().auth().oauth2(completo("CONTA-002")).put("/api/v1/ordens/" + id + "/cancelamento").then().statusCode(202);
        given().auth().oauth2(completo("CONTA-002")).get("/api/v1/ordens/" + id).then()
                .body("cancelamento.estado", equalTo("SOLICITADO"));
        given().auth().oauth2(completo("CONTA-002")).get("/api/v1/ordens").then()
                .body("ordens[0].cancelamento.estado", equalTo("SOLICITADO"));
    }
}
