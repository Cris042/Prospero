package br.prospero.mo.risco;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RF08c (ADR-016): com MO_RECUSAR_LIQUIDACAO_PENDENTE ligada, a preparação de conta com crédito D+2 pendente é 409
 * LIQUIDACAO_PENDENTE antes de qualquer escrita. A flag nasce desligada até existir o emissor (RF08d/RF08e).
 */
@QuarkusTest
@TestProfile(PreparacaoLiquidacaoPendenteTest.Ligada.class)
class PreparacaoLiquidacaoPendenteTest {
    public static class Ligada implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("prospero.risco.preparacao.recusar-liquidacao-pendente", "true");
        }
    }

    @Inject RepositorioRiscoMemoria repositorio;

    private static String token() {
        return Jwt.issuer("prospero-local").audience("prospero-middle-office").groups(Set.of("risco:preparacao"))
                .expiresIn(300).signWithSecret("chave-sintetica-de-teste-com-mais-de-32-bytes");
    }

    @AfterEach
    void limpar() {
        repositorio.limparLiquidacoesPendentes();
    }

    @Test
    void contaComCreditoPendenteRecebe409SemEscrita() {
        repositorio.marcarLiquidacaoPendente("CONTA-002");
        var antes = repositorio.carteira("CONTA-002").orElseThrow();
        given().auth().oauth2(token()).contentType(ContentType.JSON).body(Map.of("saldo", "5.00"))
                .when().put("/api/v1/preparacoes-risco/contas/CONTA-002").then().statusCode(409)
                .contentType("application/problem+json").body("codigo", equalTo("LIQUIDACAO_PENDENTE"));
        assertEquals(antes, repositorio.carteira("CONTA-002").orElseThrow(), "nenhuma escrita antes da recusa");
    }

    @Test
    void contaSemPendenciaContinuaPreparavelEOutraContaNaoEAfetada() {
        repositorio.marcarLiquidacaoPendente("CONTA-002");
        given().auth().oauth2(token()).contentType(ContentType.JSON).body(Map.of("saldo", "7.00"))
                .when().put("/api/v1/preparacoes-risco/contas/CONTA-003").then().statusCode(200)
                .body("saldoTotal", equalTo("7.00"));
    }

    @Test
    void entradaInvalidaContinuaSendo400AntesDaPendencia() {
        repositorio.marcarLiquidacaoPendente("CONTA-002");
        given().auth().oauth2(token()).contentType(ContentType.JSON).body(Map.of("saldo", "-1"))
                .when().put("/api/v1/preparacoes-risco/contas/CONTA-002").then().statusCode(400)
                .body("codigo", equalTo("SALDO_INVALIDO"));
    }
}
