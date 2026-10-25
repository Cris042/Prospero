package br.prospero.fo.ordens;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class TrajetoriaContratoTest {
    private static final String CHAVE = "chave-sintetica-de-teste-com-mais-de-32-bytes";

    private static String token(String conta, String audiencia, String... grupos) {
        return Jwt.issuer("prospero-local").audience(audiencia).subject(conta).groups(Set.of(grupos))
                .expiresIn(300).signWithSecret(CHAVE);
    }

    private static String rota() {
        return "/api/v1/ordens/" + UUID.randomUUID() + "/trajetoria";
    }

    @Test
    void documentaConsultaDaTrajetoriaDuravel() {
        given().accept("application/json").when().get("/q/openapi").then()
                .statusCode(200)
                .body("paths.'/api/v1/ordens/{id}/trajetoria'.get", notNullValue());
    }

    @Test
    void exigeAutenticacaoAudienciaEEscopoDeLeitura() {
        given().when().get(rota()).then().statusCode(401);
        given().auth().oauth2(token("CONTA-002", "prospero-back-office", "ordens:leitura"))
                .when().get(rota()).then().statusCode(401);
        given().auth().oauth2(token("CONTA-002", "prospero-front-office", "ordens:enviar"))
                .when().get(rota()).then().statusCode(403);
    }

    @Test
    void rejeitaContaDesconhecidaSemConsultarHistorico() {
        given().auth().oauth2(token("CONTA-999", "prospero-front-office", "ordens:leitura"))
                .when().get(rota()).then().statusCode(403)
                .body("codigo", equalTo("CONTA_DESCONHECIDA"));
    }

    @Test
    void validaLimitesECursorAntesDeConsultarHistorico() {
        String credencial = token("CONTA-002", "prospero-front-office", "ordens:leitura");
        for (int limite : new int[]{0, -1, 101}) {
            given().auth().oauth2(credencial).queryParam("limite", limite).when().get(rota()).then()
                    .statusCode(400).contentType("application/problem+json");
        }
        given().auth().oauth2(credencial).queryParam("cursor", "cursor-invalido")
                .when().get(rota()).then().statusCode(400).contentType("application/problem+json");
    }
}
