package br.prospero.bo.liquidacao;

import br.prospero.bo.liquidacao.actor.MovimentoActor;
import br.prospero.bo.liquidacao.dominio.MovimentoInvalidoException;
import br.prospero.bo.liquidacao.dto.MovimentoResultadoDTO;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(MovimentoResourceTest.Perfil.class)
class MovimentoResourceTest {
    static final String ROTA = "/api/v1/data-movimento";
    static final String CHAVE = "chave-sintetica-de-teste-com-mais-de-32-bytes";
    @Inject Actor actor;

    public static class Perfil implements QuarkusTestProfile {
        @Override public Set<Class<?>> getEnabledAlternatives() { return Set.of(Actor.class); }
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("prospero.liquidacao.relogio-habilitado", "true");
        }
    }

    @ApplicationScoped @Alternative
    public static class Actor extends MovimentoActor {
        private int chamadas;
        private LocalDate data;
        private String sub;
        private String jti;
        private MovimentoInvalidoException erro;

        @Override public MovimentoResultadoDTO avancar(LocalDate alvo, String sub, String jti) {
            chamadas++;
            this.data = alvo;
            this.sub = sub;
            this.jti = jti;
            if (erro != null) throw erro;
            return new MovimentoResultadoDTO(alvo.toString(), 2);
        }

        public void reiniciar() { chamadas = 0; data = null; sub = null; jti = null; erro = null; }
        public int chamadas() { return chamadas; }
        public LocalDate data() { return data; }
        public String sub() { return sub; }
        public String jti() { return jti; }
        public void falhar(MovimentoInvalidoException erro) { this.erro = erro; }
    }

    @BeforeEach void preparar() { actor.reiniciar(); }

    @Test
    void tokenAdministrativoAvancaEPassaIdentidadeParaAuditoria() {
        given().auth().oauth2(token("liquidacao:administrar")).contentType("application/json")
                .body("{\"dataAlvo\":\"2026-10-27\"}").post(ROTA).then().statusCode(200)
                .header("Cache-Control", containsString("no-store"))
                .body("dataMovimento", equalTo("2026-10-27")).body("liquidacoes", equalTo(2));
        assertEquals(1, actor.chamadas());
        assertEquals(LocalDate.of(2026, 10, 27), actor.data());
        assertEquals("operador-local", actor.sub());
        assertEquals("jti-qa-rf08d", actor.jti());
    }

    @Test
    void tokenAusenteAudienciaErradaEEscopoDeLeituraNaoAlcancamActor() {
        given().contentType("application/json").body("{\"dataAlvo\":\"2026-10-27\"}")
                .post(ROTA).then().statusCode(401);
        given().auth().oauth2(token("razao:leitura")).contentType("application/json")
                .body("{\"dataAlvo\":\"2026-10-27\"}").post(ROTA).then().statusCode(403);
        String audienciaErrada = Jwt.issuer("prospero-local").audience("prospero-front-office")
                .subject("operador-local").groups(Set.of("liquidacao:administrar"))
                .claim("jti", "jti-qa-rf08d").expiresIn(300).signWithSecret(CHAVE);
        given().auth().oauth2(audienciaErrada).contentType("application/json")
                .body("{\"dataAlvo\":\"2026-10-27\"}").post(ROTA).then().statusCode(401);
        assertEquals(0, actor.chamadas());
    }

    @Test
    void corpoAusenteDataNulaInvalidaOuFormatoIncorretoSao400SemEscrita() {
        for (String corpo : new String[]{"{}", "null", "{\"dataAlvo\":null}", "{\"dataAlvo\":\"2026-02-30\"}",
                "{\"dataAlvo\":\"27/10/2026\"}", "{"}) {
            given().auth().oauth2(token("liquidacao:administrar")).contentType("application/json")
                    .body(corpo).post(ROTA).then().statusCode(400);
        }
        assertEquals(0, actor.chamadas());
    }

    @Test
    void datasRetroativasELimiteExcedidoPreservamStatusECodigoDoDominio() {
        actor.falhar(new MovimentoInvalidoException(409, "DATA_RETROATIVA"));
        given().auth().oauth2(token("liquidacao:administrar")).contentType("application/json")
                .body("{\"dataAlvo\":\"2026-10-22\"}").post(ROTA).then().statusCode(409)
                .contentType(startsWith("application/problem+json")).body("codigo", equalTo("DATA_RETROATIVA"));
        actor.falhar(new MovimentoInvalidoException(422, "LIMITE_AVANCO"));
        given().auth().oauth2(token("liquidacao:administrar")).contentType("application/json")
                .body("{\"dataAlvo\":\"2027-03-01\"}").post(ROTA).then().statusCode(422)
                .contentType(startsWith("application/problem+json")).body("codigo", equalTo("LIMITE_AVANCO"));
    }

    static String token(String grupo) {
        return Jwt.issuer("prospero-local").audience("prospero-back-office").subject("operador-local")
                .groups(Set.of(grupo)).claim("jti", "jti-qa-rf08d").expiresIn(300).signWithSecret(CHAVE);
    }
}
