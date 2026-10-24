package br.prospero.mo.liquidacao;

import br.prospero.mo.liquidacao.dto.CreditosDTO.CreditoDTO;
import br.prospero.mo.liquidacao.jpa.RepositorioLiquidacaoJpa;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Contrato REST real; só a fonte de persistência é substituída, mantendo actor e autenticação. */
@QuarkusTest
@TestProfile(CreditosResourceTest.Perfil.class)
class CreditosResourceTest {
    private static final String CHAVE = "chave-sintetica-de-teste-com-mais-de-32-bytes";
    private static final String NEGOCIO = "ALFA3-2026-10-23-1";
    private static final String ROTA = "/api/v1/risco/liquidacoes/";
    @Inject Fonte fonte;

    public static class Perfil implements QuarkusTestProfile {
        @Override public Set<Class<?>> getEnabledAlternatives() { return Set.of(Fonte.class); }
    }

    @ApplicationScoped @Alternative
    public static class Fonte extends RepositorioLiquidacaoJpa {
        private final List<String> consultas = new ArrayList<>();
        @Override public List<CreditoDTO> consultarCreditos(String conta, String negocio) {
            consultas.add(conta + "|" + negocio);
            if (!conta.equals("CONTA-002") || !negocio.equals(NEGOCIO)) return List.of();
            return List.of(new CreditoDTO(NEGOCIO, "COMPRADOR", "ALFA3", "CUSTODIA", "4", false),
                    new CreditoDTO(NEGOCIO, "VENDEDOR", "ALFA3", "CAIXA", "36.00", true));
        }
        public void reiniciar() { consultas.clear(); }
        public List<String> consultas() { return List.copyOf(consultas); }
    }

    @BeforeEach void preparar() { fonte.reiniciar(); }

    @Test
    void retornaNoMaximoDoisLadosDaContaDoJwtSemContraparteEComValoresTextuais() {
        given().auth().oauth2(token("CONTA-002", "risco:leitura")).queryParam("conta", "CONTA-003")
                .header("X-Conta", "CONTA-003").get(ROTA + NEGOCIO).then().statusCode(200)
                .header("Cache-Control", containsString("no-store"))
                .body("conta", equalTo("CONTA-002")).body("creditos", hasSize(2))
                .body("creditos.lado", contains("COMPRADOR", "VENDEDOR"))
                .body("creditos.valor", contains("4", "36.00"))
                .body("creditos.creditado", contains(false, true))
                .body("creditos.recurso", contains("CUSTODIA", "CAIXA"))
                .body(not(containsString("CONTA-003"))).body(not(containsString("contraparte")));
        assertEquals(List.of("CONTA-002|" + NEGOCIO), fonte.consultas());
    }

    @Test
    void negocioAlheioEAusenteSao200VaziosIndistinguiveis() {
        for (String negocio : List.of(NEGOCIO, "ALFA3-2026-10-23-999")) {
            given().auth().oauth2(token("CONTA-003", "risco:leitura")).get(ROTA + negocio).then().statusCode(200)
                    .header("Cache-Control", containsString("no-store"))
                    .body("conta", equalTo("CONTA-003")).body("creditos", hasSize(0));
        }
    }

    @Test
    void ausenciaEscopoAudienciaExpiracaoEContaInvalidaNaoAlcancamRepositorio() {
        given().get(ROTA + NEGOCIO).then().statusCode(401);
        given().auth().oauth2(token("CONTA-002", "risco:preparacao")).get(ROTA + NEGOCIO).then().statusCode(403);
        given().auth().oauth2(token("CONTA-999", "risco:leitura")).get(ROTA + NEGOCIO).then().statusCode(403);
        String outraAudiencia = Jwt.issuer("prospero-local").audience("prospero-back-office").subject("CONTA-002")
                .groups(Set.of("risco:leitura")).expiresIn(300).signWithSecret(CHAVE);
        given().auth().oauth2(outraAudiencia).get(ROTA + NEGOCIO).then().statusCode(401);
        String expirado = Jwt.issuer("prospero-local").audience("prospero-middle-office").subject("CONTA-002")
                .groups(Set.of("risco:leitura")).issuedAt(Instant.now().minusSeconds(7200))
                .expiresAt(Instant.now().minusSeconds(3600)).signWithSecret(CHAVE);
        given().auth().oauth2(expirado).get(ROTA + NEGOCIO).then().statusCode(401);
        assertEquals(List.of(), fonte.consultas());
    }

    @Test
    void identificadorMalformadoERecusadoAntesDaConsultaSemEcoarEntrada() {
        for (String negocio : List.of("alfa3-2026-10-23-1", "ALFA3-2026-10-23-0", "ALFA3-2026-10-23-" + "1".repeat(19),
                "INVALIDO-2026-10-23-1")) {
            given().auth().oauth2(token("CONTA-002", "risco:leitura")).get(ROTA + negocio).then().statusCode(400)
                    .body("codigo", equalTo("CONSULTA_INVALIDA")).body("detail", not(containsString(negocio)));
        }
        assertEquals(List.of(), fonte.consultas());
    }

    @Test
    void rotaNaoAceitaEscritaEDocumentaRespostas() {
        given().auth().oauth2(token("CONTA-002", "risco:leitura")).post(ROTA + NEGOCIO).then().statusCode(405);
        given().get("/q/openapi?format=json").then().statusCode(200)
                .body("paths.'/api/v1/risco/liquidacoes/{negocio}'.get.responses", hasKey("200"))
                .body("paths.'/api/v1/risco/liquidacoes/{negocio}'.get.responses", hasKey("401"))
                .body("paths.'/api/v1/risco/liquidacoes/{negocio}'.get.responses", hasKey("403"));
    }

    private static String token(String conta, String grupo) {
        return Jwt.issuer("prospero-local").audience("prospero-middle-office").subject(conta)
                .groups(Set.of(grupo)).expiresIn(300).signWithSecret(CHAVE);
    }
}
