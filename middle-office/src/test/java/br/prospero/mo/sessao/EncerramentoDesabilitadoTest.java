package br.prospero.mo.sessao;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** QA RF05c.4b: com a flag desligada (padrão fora do ambiente local) o encerramento é 404 e nada vai à outbox. */
@QuarkusTest
@TestProfile(EncerramentoDesabilitadoTest.Desligado.class)
class EncerramentoDesabilitadoTest {
    public static class Desligado implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("prospero.sessao.encerramento.habilitado", "false");
        }
    }

    @Inject RepositorioSessaoMemoria repositorio;

    @Test
    void flagDesligadaResponde404SemGravarComando() {
        String tok = Jwt.issuer("prospero-local").audience("prospero-middle-office").groups(Set.of("sessao:encerrar"))
                .expiresIn(300).signWithSecret("chave-sintetica-de-teste-com-mais-de-32-bytes");
        given().auth().oauth2(tok).when().post("/api/v1/sessoes/ALFA3/2026-10-18/encerramento")
                .then().statusCode(404).body("codigo", equalTo("ENCERRAMENTO_DESABILITADO"));
        given().auth().oauth2(tok).when().post("/api/v1/sessoes/ZZZZ3/data-ruim/encerramento")
                .then().statusCode(404).body("codigo", equalTo("ENCERRAMENTO_DESABILITADO"));
        given().when().post("/api/v1/sessoes/ALFA3/2026-10-18/encerramento").then().statusCode(401);
        assertTrue(repositorio.comandos().isEmpty());
    }
}
