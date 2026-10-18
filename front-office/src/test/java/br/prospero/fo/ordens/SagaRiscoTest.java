package br.prospero.fo.ordens;

import br.prospero.fo.comum.dominio.MensagemInvalidaException;
import br.prospero.fo.comum.dominio.MensagemSaida;
import br.prospero.fo.ordens.actor.AplicarRiscoActor;
import br.prospero.fo.ordens.dominio.EstadoOrdem;
import br.prospero.fo.ordens.dominio.MotivoRejeicao;
import br.prospero.fo.ordens.dominio.ResultadoRisco;
import br.prospero.fo.ordens.helper.EnvelopeRisco;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RF04 (ADR-012): comando de risco na outbox do registro e transição condicional pelo resultado do MO. */
@QuarkusTest
class SagaRiscoTest {
    private static final String CHAVE = "chave-sintetica-de-teste-com-mais-de-32-bytes";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    RepositorioOrdensMemoria repositorio;

    @Inject
    FonteUltimoPrecoControlada ultimos;

    @Inject
    AplicarRiscoActor aplicar;

    @BeforeEach
    void limpar() {
        repositorio.limparTudo();
        ultimos.limpar();
    }

    private static String token(String conta) {
        return Jwt.issuer("prospero-local").audience("prospero-front-office").subject(conta)
                .groups(Set.of("ordens:enviar", "ordens:leitura")).expiresIn(300).signWithSecret(CHAVE);
    }

    private static String enviar(String conta, String chave, String caminho, Object corpo) {
        return given().auth().oauth2(token(conta)).header("X-Idempotency-Key", chave).contentType(ContentType.JSON)
                .body(corpo).when().post(caminho).then().statusCode(202).extract().path("id");
    }

    private static Map<String, Object> market() {
        return new HashMap<>(Map.of("ticker", "BETA3", "lado", "COMPRA", "tipo", "MARKET", "validade", "IOC",
                "quantidade", 10));
    }

    private JsonNode unicoComando() throws Exception {
        assertEquals(1, repositorio.outbox().size());
        MensagemSaida m = repositorio.outbox().getFirst();
        assertEquals(EnvelopeRisco.TOPICO_COMANDOS, m.topico());
        return JSON.readTree(m.payload());
    }

    @Test
    void registroGravaComandoComBaseInclusiveParaMarketERepeticaoNaoDuplica() throws Exception {
        String id = enviar("CONTA-002", "saga-0001", "/api/v1/ordens", market());
        enviar("CONTA-002", "saga-0001", "/api/v1/ordens", market());
        JsonNode comando = unicoComando();
        MensagemSaida m = repositorio.outbox().getFirst();
        assertEquals("CONTA-002", m.chave(), "chave de partição é a conta");
        assertEquals(m.id().toString(), comando.get("id").asText());
        assertEquals("AVALIAR_RISCO", comando.get("tipo").asText());
        assertEquals(1, comando.get("versao").asInt());
        assertEquals("front-office", comando.get("origem").asText());
        assertEquals(id, comando.get("correlacao").asText());
        assertEquals("20.00", comando.at("/dados/precoBase").asText(), "Market leva a base para a reserva");
        assertTrue(comando.at("/dados/ordens/0/precoLimite").isNull());
        assertEquals(10, comando.at("/dados/ordens/0/quantidade").asLong());
        // A ordem Market continua sem base gravada (ADR-011).
        given().auth().oauth2(token("CONTA-002")).when().get("/api/v1/ordens/" + id).then().statusCode(200)
                .body("precoBaseTunel", nullValue()).body("estado", equalTo("RECEBIDA"))
                .body("motivoRejeicao", nullValue());
    }

    @Test
    void ocoGeraUmComandoComAsDuasPernas() throws Exception {
        Map<String, Object> oco = Map.of("ticker", "DELTA3", "lado", "VENDA", "quantidade", 5, "validade", "DIA",
                "takeProfit", Map.of("precoLimite", "42.00"),
                "stopLoss", Map.of("tipo", "STOP_LIMIT", "precoGatilho", "38.00", "precoLimite", "37.50"));
        given().auth().oauth2(token("CONTA-003")).header("X-Idempotency-Key", "saga-0002").contentType(ContentType.JSON)
                .body(oco).when().post("/api/v1/ordens-oco").then().statusCode(202);
        JsonNode comando = unicoComando();
        assertEquals(2, comando.at("/dados/ordens").size());
        assertEquals("VENDA", comando.at("/dados/lado").asText());
        assertEquals("LIMIT", comando.at("/dados/ordens/0/tipo").asText());
        assertEquals("STOP_LIMIT", comando.at("/dados/ordens/1/tipo").asText());
    }

    @Test
    void recusaSincronaNaoGravaComando() {
        Map<String, Object> fora = new HashMap<>(Map.of("ticker", "ALFA3", "lado", "COMPRA", "tipo", "LIMIT",
                "validade", "DIA", "quantidade", 1, "precoLimite", "99.00"));
        given().auth().oauth2(token("CONTA-002")).header("X-Idempotency-Key", "saga-0003").contentType(ContentType.JSON)
                .body(fora).when().post("/api/v1/ordens").then().statusCode(422);
        assertTrue(repositorio.outbox().isEmpty());
    }

    @Test
    void resultadoAvancaUmaVezSomenteNaContaDaIntencao() {
        String id = enviar("CONTA-002", "saga-0004", "/api/v1/ordens", market());
        UUID grupo = UUID.fromString(id);
        ResultadoRisco rejeicao = new ResultadoRisco(UUID.randomUUID(), grupo, "CONTA-002", EstadoOrdem.REJEITADA,
                MotivoRejeicao.SALDO_INSUFICIENTE);
        assertFalse(aplicar.aplicar(new ResultadoRisco(UUID.randomUUID(), grupo, "CONTA-003", EstadoOrdem.RESERVADA,
                null)), "outra conta não altera a ordem");
        assertTrue(aplicar.aplicar(rejeicao));
        assertFalse(aplicar.aplicar(rejeicao), "reentrega é idempotente");
        assertFalse(aplicar.aplicar(new ResultadoRisco(UUID.randomUUID(), grupo, "CONTA-002", EstadoOrdem.RESERVADA,
                null)), "ordem decidida não muda de novo");
        given().auth().oauth2(token("CONTA-002")).when().get("/api/v1/ordens/" + id).then().statusCode(200)
                .body("estado", equalTo("REJEITADA")).body("motivoRejeicao", equalTo("SALDO_INSUFICIENTE"));
    }

    @Test
    void parDeOcoMudaJunto() {
        Map<String, Object> oco = Map.of("ticker", "DELTA3", "lado", "VENDA", "quantidade", 5, "validade", "DIA",
                "takeProfit", Map.of("precoLimite", "42.00"), "stopLoss", Map.of("tipo", "STOP", "precoGatilho", "38.00"));
        String grupo = given().auth().oauth2(token("CONTA-003")).header("X-Idempotency-Key", "saga-0005")
                .contentType(ContentType.JSON).body(oco).when().post("/api/v1/ordens-oco").then().statusCode(202)
                .extract().path("grupoOco");
        assertTrue(aplicar.aplicar(new ResultadoRisco(UUID.randomUUID(), UUID.fromString(grupo), "CONTA-003",
                EstadoOrdem.RESERVADA, null)));
        given().auth().oauth2(token("CONTA-003")).when().get("/api/v1/ordens-oco/" + grupo).then().statusCode(200)
                .body("ordens.estado", org.hamcrest.Matchers.everyItem(equalTo("RESERVADA")));
    }

    private static byte[] resultado(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String envelope(String origem, String dados) {
        return """
                {"id":"%s","tipo":"RESULTADO_RISCO","versao":1,"origem":"%s","correlacao":"%s",
                 "ocorridoEm":"2026-10-18T12:00:00Z","conta":"CONTA-002","traceparent":null,"dados":%s}"""
                .formatted(UUID.randomUUID(), origem, UUID.randomUUID(), dados);
    }

    @Test
    void resultadoForaDoContratoENegado() {
        String ok = "{\"resultado\":\"REJEITADA\",\"motivo\":\"SALDO_INSUFICIENTE\",\"valorReservado\":null,\"quantidadeReservada\":null}";
        String reservada = "{\"resultado\":\"RESERVADA\",\"motivo\":null,\"valorReservado\":\"10.00\",\"quantidadeReservada\":null}";
        ResultadoRisco lido = EnvelopeRisco.resultado(resultado(envelope("middle-office", ok)));
        assertEquals(MotivoRejeicao.SALDO_INSUFICIENTE, lido.motivo());
        assertEquals(EstadoOrdem.RESERVADA, EnvelopeRisco.resultado(resultado(envelope("middle-office", reservada))).estado());
        assertEquals(EstadoOrdem.RESERVADA, EnvelopeRisco.resultado(resultado(envelope("middle-office",
                reservada.replace("\"10.00\"", "null").replace("null}", "3}")))).estado(), "custódia");
        for (String invalido : new String[]{
                envelope("front-office", ok),
                envelope("middle-office", ok.replace("SALDO_INSUFICIENTE", "INVENTADO")),
                envelope("middle-office", ok.replace("\"REJEITADA\",\"motivo\":\"SALDO_INSUFICIENTE\"",
                        "\"RESERVADA\",\"motivo\":\"SALDO_INSUFICIENTE\"")),
                envelope("middle-office", ok.replace("\"REJEITADA\"", "\"EXECUTADA\"")),
                envelope("middle-office", ok.replace("}", ",\"extra\":1}")),
                envelope("middle-office", ok).replace("\"versao\":1", "\"versao\":2"),
                envelope("middle-office", ok).replace("\"versao\":1", "\"versao\":1,\"versao\":1"),
                envelope("middle-office", ok).replace("CONTA-002", "conta-x"),
                envelope("middle-office", ok).replace("2026-10-18T12:00:00Z", "ontem"),
                envelope("middle-office", ok).replace("\"traceparent\":null", "\"traceparent\":\"x\""),
                envelope("middle-office", ok.replace("\"valorReservado\":null", "\"valorReservado\":\"10.00\"")),
                envelope("middle-office", reservada.replace("\"10.00\"", "10.00")),
                envelope("middle-office", reservada.replace("\"10.00\"", "null")),
                envelope("middle-office", reservada.replace("null}", "5}")),
                "{", "[]", "x".repeat(EnvelopeRisco.TAMANHO_MAXIMO + 1)}) {
            assertThrows(MensagemInvalidaException.class, () -> EnvelopeRisco.resultado(resultado(invalido)), invalido);
        }
    }
}
