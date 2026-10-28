package br.prospero.bo.posicoes;

import br.prospero.bo.posicoes.dominio.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(PosicoesResourceTest.Perfil.class)
class PosicoesResourceTest {
    private static final String CHAVE = "chave-sintetica-de-teste-com-mais-de-32-bytes";
    @Inject Fonte fonte;
    @Inject Situacao situacao;

    public static class Perfil implements QuarkusTestProfile {
        @Override public Set<Class<?>> getEnabledAlternatives() { return Set.of(Fonte.class, Situacao.class); }
    }

    @ApplicationScoped @Alternative
    public static class Fonte implements ConsultaPosicoes {
        private List<EstadoPosicao> estados;
        private String ultima;
        public void reiniciar() {
            estados = new ArrayList<>();
            for (String ticker : List.of("ALFA3", "BETA3", "DELTA3", "GAMA3", "OMEGA3")) {
                estados.add(new EstadoPosicao(ticker, Posicao.vazia(), null, null, false, 0, null));
            }
            estados.set(0, new EstadoPosicao("ALFA3", Posicao.vazia().aplicar(10, new BigDecimal("100.00"))
                    .aplicar(-4, new BigDecimal("110.00")), new BigDecimal("105.00"), hoje(), false, 3,
                    Instant.parse("2026-10-23T00:00:00Z")));
            ultima = null;
        }
        public void marcarAntigaOuEncerrada(boolean encerrada) {
            EstadoPosicao e = estados.getFirst();
            estados.set(0, new EstadoPosicao(e.ticker(), e.posicao(), e.cotacao(),
                    encerrada ? hoje() : hoje().minusDays(1), encerrada, e.revisao(), e.atualizadaEm()));
        }
        public void excederOrcamentoNaMarcacao() {
            BigInteger base = BigInteger.ONE.shiftLeft(Racional.LIMITE_BITS - 1);
            Posicao p = new Posicao(1000003, Racional.fracao(base.add(BigInteger.ONE), base.subtract(BigInteger.ONE)), Racional.ZERO);
            estados.set(0, new EstadoPosicao("ALFA3", p, new BigDecimal("2.00"), hoje(), false, 1, Instant.now()));
        }
        @Override public List<EstadoPosicao> consultar(String conta) { ultima = conta; return List.copyOf(estados); }
        public String ultimaConta() { return ultima; }
    }

    @ApplicationScoped @Alternative
    public static class Situacao implements SituacaoPosicoes {
        private boolean pronta;
        private boolean cair;
        private int leituras;
        public void definir(boolean pronta) { this.pronta = pronta; cair = false; leituras = 0; }
        public void perderDuranteConsulta() { pronta = true; cair = true; leituras = 0; }
        @Override public boolean emDia() { return pronta && (!cair || ++leituras == 1); }
    }

    @BeforeEach void preparar() { fonte.reiniciar(); situacao.definir(true); }

    @Test
    void apresentaCincoAtivosComValoresTextuaisEBaseExplicita() {
        given().auth().oauth2(token("CONTA-002", "posicoes:leitura")).get("/api/v1/posicoes").then()
                .statusCode(200).header("Cache-Control", containsString("no-store"))
                .body("conta", equalTo("CONTA-002")).body("base", equalTo("NEGOCIOS_DESDE_ORIGEM"))
                .body("sincronizado", equalTo(true)).body("posicoes", hasSize(5))
                .body("posicoes[0].ticker", equalTo("ALFA3")).body("posicoes[0].quantidade", equalTo("6"))
                .body("posicoes[0].lado", equalTo("COMPRADA")).body("posicoes[0].precoMedio", equalTo("100.0000"))
                .body("posicoes[0].resultadoRealizado", equalTo("40.0000"))
                .body("posicoes[0].resultadoNaoRealizado", equalTo("30.0000"))
                .body("posicoes[0].cotacao", equalTo("105.00")).body("posicoes[0].cotacaoDefasada", equalTo(false))
                .body("posicoes[1].quantidade", equalTo("0")).body("posicoes[1].lado", equalTo("ZERADA"))
                .body("posicoes[1].precoMedio", nullValue()).body("posicoes[1].cotacao", nullValue())
                .body("posicoes[1].resultadoNaoRealizado", nullValue()).body("posicoes[1].cotacaoDefasada", equalTo(true));
    }

    @Test
    void contaDoTokenNaoPodeSerSubstituidaPorQueryOuCabecalho() {
        given().auth().oauth2(token("CONTA-002", "posicoes:leitura")).queryParam("conta", "CONTA-001")
                .header("X-Conta", "CONTA-001").get("/api/v1/posicoes").then().statusCode(200)
                .body("conta", equalTo("CONTA-002"));
        assertEquals("CONTA-002", fonte.ultimaConta());
    }

    @Test
    void contaDeMedicao005TemPosicoesComoAsDemais() {
        given().auth().oauth2(token("CONTA-005", "posicoes:leitura")).get("/api/v1/posicoes").then()
                .statusCode(200).body("conta", equalTo("CONTA-005"));
        assertEquals("CONTA-005", fonte.ultimaConta());
        given().auth().oauth2(token("CONTA-999", "posicoes:leitura")).get("/api/v1/posicoes").then().statusCode(403);
    }

    @Test
    void negaSemTokenSemEscopoEContaDesconhecida() {
        given().get("/api/v1/posicoes").then().statusCode(401).header("Cache-Control", containsString("no-store"));
        given().auth().oauth2(token("CONTA-002", "ordens:leitura")).get("/api/v1/posicoes").then().statusCode(403);
        given().auth().oauth2(token("CONTA-999", "posicoes:leitura")).get("/api/v1/posicoes").then().statusCode(403);
    }

    @Test
    void negaAudienciaEmissorEValidadeIncorretos() {
        String audiencia = Jwt.issuer("prospero-local").audience("prospero-front-office").subject("CONTA-002")
                .groups(Set.of("posicoes:leitura")).expiresIn(300).signWithSecret(CHAVE);
        String emissor = Jwt.issuer("outro-emissor").audience("prospero-back-office").subject("CONTA-002")
                .groups(Set.of("posicoes:leitura")).expiresIn(300).signWithSecret(CHAVE);
        String expirado = Jwt.issuer("prospero-local").audience("prospero-back-office").subject("CONTA-002")
                .groups(Set.of("posicoes:leitura")).issuedAt(Instant.now().minusSeconds(7200))
                .expiresAt(Instant.now().minusSeconds(3600)).signWithSecret(CHAVE);
        for (String invalido : List.of(audiencia, emissor, expirado)) {
            given().auth().oauth2(invalido).get("/api/v1/posicoes").then().statusCode(401);
        }
    }

    @Test
    void reconstruindoOuPerdendoSincronizacaoNuncaRespondeZeroConfirmado() {
        situacao.definir(false);
        given().auth().oauth2(token("CONTA-002", "posicoes:leitura")).get("/api/v1/posicoes").then()
                .statusCode(503).contentType(startsWith("application/problem+json"))
                .header("Cache-Control", containsString("no-store"));
        situacao.perderDuranteConsulta();
        given().auth().oauth2(token("CONTA-002", "posicoes:leitura")).get("/api/v1/posicoes").then().statusCode(503);
    }

    @Test
    void cotacaoAntigaOuSessaoEncerradaMantemSnapshotMasSinalizaDefasagem() {
        for (boolean encerrada : new boolean[]{false, true}) {
            fonte.marcarAntigaOuEncerrada(encerrada);
            given().auth().oauth2(token("CONTA-002", "posicoes:leitura")).get("/api/v1/posicoes").then()
                    .statusCode(200).body("posicoes[0].cotacaoDefasada", equalTo(true))
                    .body("posicoes[0].resultadoRealizado", equalTo("40.0000"));
        }
    }

    @Test
    void orcamentoExcedidoNaMarcacaoRespondeIndisponibilidadeSemSnapshotParcial() {
        fonte.excederOrcamentoNaMarcacao();
        given().auth().oauth2(token("CONTA-002", "posicoes:leitura")).get("/api/v1/posicoes").then()
                .statusCode(503).contentType(startsWith("application/problem+json"))
                .header("Cache-Control", containsString("no-store")).body("$", not(hasKey("posicoes")));
    }

    private static String token(String conta, String... grupos) {
        return Jwt.issuer("prospero-local").audience("prospero-back-office").subject(conta).groups(Set.of(grupos))
                .expiresIn(300).signWithSecret(CHAVE);
    }
    private static LocalDate hoje() { return LocalDate.now(ZoneId.of("America/Sao_Paulo")); }
}
