package br.prospero.mo.mensageria.rest;

import br.prospero.mo.mensageria.MensageriaMemoria;
import br.prospero.mo.mensageria.dominio.MensagemQuarentena;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** ADR-019: rota operacional com escopo próprio; a listagem nunca devolve o payload. */
@QuarkusTest
class QuarentenaResourceTest {
    private static final String CHAVE = "chave-sintetica-de-teste-com-mais-de-32-bytes";
    @Inject MensageriaMemoria memoria;

    private static String token(String... grupos) {
        return Jwt.issuer("prospero-local").audience("prospero-middle-office").groups(Set.of(grupos)).expiresIn(300)
                .signWithSecret(CHAVE);
    }

    @BeforeEach
    void preparar() {
        memoria.quarentena().limpar();
        memoria.registrar(new MensagemQuarentena("topico.sem.leitor.v1", 2, 41, "CONTA-001", null,
                "{\"conta\":\"CONTA-001\",\"segredo-do-payload\":1}".getBytes(StandardCharsets.UTF_8), "contrato", 1));
    }

    @Test
    void exigeTokenEEscopoProprio() {
        given().when().get("/api/v1/quarentena").then().statusCode(401);
        given().auth().oauth2(token("sessao:encerrar")).when().get("/api/v1/quarentena").then().statusCode(403);
        given().auth().oauth2(token("sessao:encerrar")).when().post("/api/v1/quarentena/1/reprocessamento").then().statusCode(403);
        given().auth().oauth2(token("sessao:encerrar")).when().post("/api/v1/quarentena/1/descarte").then().statusCode(403);
    }

    @Test
    void listaSemPayloadEResolveComIdempotencia() {
        String tok = token("quarentena:reprocessar");
        given().auth().oauth2(tok).when().get("/api/v1/quarentena").then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("size()", equalTo(1)).body("[0].topico", equalTo("topico.sem.leitor.v1"))
                .body("[0].particao", equalTo(2)).body("[0].offset", equalTo(41)).body("[0].reprocessavel", equalTo(true))
                .body(not(containsString("segredo-do-payload"))).body("[0]", not(hasKey("payload")));
        given().auth().oauth2(tok).when().get("/api/v1/quarentena?limite=0").then().statusCode(400)
                .body("codigo", equalTo("LIMITE_INVALIDO"));
        given().auth().oauth2(tok).when().post("/api/v1/quarentena/1/reprocessamento").then().statusCode(422)
                .body("codigo", equalTo("TOPICO_SEM_REPROCESSADOR"));
        given().auth().oauth2(tok).when().post("/api/v1/quarentena/999/reprocessamento").then().statusCode(404)
                .body("codigo", equalTo("QUARENTENA_DESCONHECIDA"));
        given().auth().oauth2(tok).when().post("/api/v1/quarentena/1/descarte").then().statusCode(200)
                .body("resolucao", equalTo("DESCARTADA")).body("repetido", equalTo(false));
        given().auth().oauth2(tok).when().post("/api/v1/quarentena/1/descarte").then().statusCode(200)
                .body("repetido", equalTo(true));
        given().auth().oauth2(tok).when().get("/api/v1/quarentena").then().statusCode(200).body("size()", equalTo(0));
    }
}
