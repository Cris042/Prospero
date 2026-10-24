package br.prospero.bo.liquidacao;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;

@QuarkusTest
@TestProfile(MovimentoDesabilitadoTest.Perfil.class)
class MovimentoDesabilitadoTest {
    public static class Perfil implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("prospero.liquidacao.relogio-habilitado", "false");
        }
    }

    @Test
    void flagDesligadaResponde404AntesDaAutenticacao() {
        given().contentType("application/json").body("{\"dataAlvo\":\"2026-10-27\"}")
                .post("/api/v1/data-movimento").then().statusCode(404);
        given().header("Authorization", "Bearer invalido").contentType("application/json").body("{\"dataAlvo\":\"2026-10-27\"}")
                .post("/api/v1/data-movimento").then().statusCode(404);
        given().auth().oauth2(MovimentoResourceTest.token("liquidacao:administrar"))
                .contentType("application/json").body("{\"dataAlvo\":\"2026-10-27\"}")
                .post("/api/v1/data-movimento").then().statusCode(404);
    }
}
