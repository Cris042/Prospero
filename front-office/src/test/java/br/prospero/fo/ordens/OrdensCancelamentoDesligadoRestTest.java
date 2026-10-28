package br.prospero.fo.ordens;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D4: com a emissão desligada, só novas operações respondem 503; nada é persistido nem emitido. */
@QuarkusTest
@TestProfile(OrdensCancelamentoDesligadoRestTest.Desligado.class)
class OrdensCancelamentoDesligadoRestTest {
    public static class Desligado implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("prospero.cancelamento.emissao-habilitada", "false");
        }
    }

    @Inject RepositorioCancelamentoMemoria cancelamentos;
    @Inject RepositorioOrdensMemoria ordens;

    @Test
    void novaOperacaoRecebe503SemEfeito() {
        ordens.limparTudo();
        cancelamentos.limpar();
        String token = Jwt.issuer("prospero-local").audience("prospero-front-office").subject("CONTA-002")
                .groups(Set.of("ordens:enviar", "ordens:leitura")).expiresIn(300)
                .signWithSecret("chave-sintetica-de-teste-com-mais-de-32-bytes");
        String id = given().auth().oauth2(token).header("X-Idempotency-Key", "desligado-01").contentType(ContentType.JSON)
                .body(Map.of("ticker", "ALFA3", "lado", "COMPRA", "tipo", "LIMIT", "validade", "DIA", "quantidade", 100,
                        "precoLimite", "10.10")).post("/api/v1/ordens").then().statusCode(202).extract().path("id");
        given().auth().oauth2(token).put("/api/v1/ordens/" + id + "/cancelamento").then().statusCode(503)
                .contentType("application/problem+json").body("codigo", equalTo("CANCELAMENTO_INDISPONIVEL"));
        given().auth().oauth2(token).get("/api/v1/ordens/" + id + "/cancelamento").then().statusCode(404);
        assertTrue(cancelamentos.outbox().isEmpty());
    }
}
