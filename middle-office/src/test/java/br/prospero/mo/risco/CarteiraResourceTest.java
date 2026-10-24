package br.prospero.mo.risco;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** RF04b: carteira só da conta do token e preparação restrita a contas de teste e ao escopo próprio. */
@QuarkusTest
class CarteiraResourceTest {
    private static final String CHAVE = "chave-sintetica-de-teste-com-mais-de-32-bytes";
    @jakarta.inject.Inject RepositorioRiscoMemoria repositorio;

    private static String token(String conta, String... grupos) {
        var builder = Jwt.issuer("prospero-local").audience("prospero-middle-office").groups(Set.of(grupos))
                .expiresIn(300);
        return (conta == null ? builder : builder.subject(conta)).signWithSecret(CHAVE);
    }

    private static io.restassured.response.Response preparar(String token, String conta, Object corpo) {
        return given().auth().oauth2(token).contentType(ContentType.JSON).body(corpo)
                .when().put("/api/v1/preparacoes-risco/contas/" + conta);
    }

    @Test
    void preparaEConsultaApenasAPropriaConta() {
        preparar(token(null, "risco:preparacao"), "CONTA-003", Map.of("saldo", "250.5", "custodia", Map.of("ALFA3", 7)))
                .then().statusCode(200).header("Cache-Control", "no-store")
                .body("saldoTotal", equalTo("250.50")).body("saldoReservado", equalTo("0.00"));
        given().auth().oauth2(token("CONTA-003", "risco:leitura")).when().get("/api/v1/risco/carteira").then()
                .statusCode(200).body("conta", equalTo("CONTA-003")).body("saldoDisponivel", equalTo("250.50"))
                .body("custodias.find { it.ticker == 'ALFA3' }.disponivel", equalTo(7));
    }

    @Test
    void autorizacaoEscopoEContas() {
        given().when().get("/api/v1/risco/carteira").then().statusCode(401)
                .body("codigo", equalTo("AUTENTICACAO_INVALIDA"));
        given().auth().oauth2(token("CONTA-003", "risco:preparacao")).when().get("/api/v1/risco/carteira").then()
                .statusCode(403);
        given().auth().oauth2(token("CONTA-999", "risco:leitura")).when().get("/api/v1/risco/carteira").then()
                .statusCode(403).body("codigo", equalTo("CONTA_DESCONHECIDA"));
        preparar(token(null, "risco:leitura"), "CONTA-003", Map.of("saldo", "1.00")).then().statusCode(403);
        preparar(token(null, "risco:preparacao"), "CONTA-001", Map.of("saldo", "1.00")).then().statusCode(403)
                .body("codigo", equalTo("CONTA_NAO_PREPARAVEL"));
        // RF05d: a carteira grande do robô formador não é preparável (ADR-013).
        preparar(token(null, "risco:preparacao"), "CONTA-004", Map.of("saldo", "1.00")).then().statusCode(403)
                .body("codigo", equalTo("CONTA_NAO_PREPARAVEL"));
        String audienciaErrada = Jwt.issuer("prospero-local").audience("prospero-front-office").subject("CONTA-003")
                .groups(Set.of("risco:leitura")).expiresIn(300).signWithSecret(CHAVE);
        given().auth().oauth2(audienciaErrada).when().get("/api/v1/risco/carteira").then().statusCode(401);
    }

    @Test
    void entradasInvalidas() {
        String prep = token(null, "risco:preparacao");
        preparar(prep, "CONTA-002", Map.of("saldo", "-1")).then().statusCode(400).body("codigo", equalTo("SALDO_INVALIDO"));
        preparar(prep, "CONTA-002", Map.of("saldo", "1.001")).then().statusCode(400);
        preparar(prep, "CONTA-002", Map.of("saldo", 10)).then().statusCode(400).body("codigo", equalTo("JSON_INVALIDO"));
        preparar(prep, "CONTA-002", Map.of("saldo", "1.00", "custodia", Map.of("ZETA3", 1))).then().statusCode(422)
                .body("codigo", equalTo("ATIVO_DESCONHECIDO"));
        preparar(prep, "CONTA-002", Map.of("saldo", "1.00", "custodia", Map.of("ALFA3", -1))).then().statusCode(400)
                .body("codigo", equalTo("CUSTODIA_INVALIDA"));
        preparar(prep, "CONTA-002", Map.of("saldo", "1.00", "extra", 1)).then().statusCode(400);
    }

    @Test
    void flagPadraoDesligadaPreparaMesmoComLiquidacaoPendente() {
        // RF08c: sem emissor (RF08d) toda negociação deixa expectativa pendente; a recusa só liga na RF08e.
        repositorio.marcarLiquidacaoPendente("CONTA-002");
        try {
            preparar(token(null, "risco:preparacao"), "CONTA-002", Map.of("saldo", "3.00")).then().statusCode(200)
                    .body("saldoTotal", equalTo("3.00"));
        } finally {
            repositorio.limparLiquidacoesPendentes();
        }
    }
}
