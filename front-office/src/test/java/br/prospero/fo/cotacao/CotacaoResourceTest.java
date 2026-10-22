package br.prospero.fo.cotacao;

import br.prospero.fo.cotacao.dominio.*;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class CotacaoResourceTest {
    private static final LocalDate HOJE = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    private static final String PRINCIPAL = new UUID(0, 0).toString();
    @Inject RepositorioCotacaoMemoria cotacoes;
    @Inject SituacaoCotacaoControlada situacao;

    @BeforeEach
    void limpar() { cotacoes.limpar(); situacao.definir(true); }

    @AfterEach
    void restaurar() { cotacoes.limpar(); situacao.definir(false); }

    private static String token(String grupo) {
        return Jwt.issuer("prospero-local").audience("prospero-front-office").groups(grupo)
                .expiresIn(300).signWithSecret("chave-sintetica-de-teste-com-mais-de-32-bytes");
    }

    private void preparar(LocalDate dia, boolean encerrada, Instant atualizado) {
        var sessao = new SessaoCotacao("ALFA3", dia, new BigDecimal("10.01"), 3,
                new BigDecimal("30.02"), 5, atualizado, encerrada);
        var compras = new LadoCotado(List.of(new NivelCotado(new BigDecimal("9.99"), 7, 2, 7)), 1, 7);
        var vendas = new LadoCotado(List.of(new NivelCotado(new BigDecimal("10.02"), 9, 1, 9)), 1, 9);
        cotacoes.preparar(new LivroCotado(sessao, compras, vendas));
    }

    @Test
    void principalExpoeCotacaoRealEVwapComQuatroCasas() {
        preparar(HOJE, false, Instant.now());
        given().auth().oauth2(token("mercado:leitura")).get("/api/v1/ativos").then().statusCode(200)
                .body("[0].origem", equalTo("MATCHING")).body("[0].vwap", equalTo("10.0067"))
                .body("[0].ultimoPreco", equalTo("10.01")).body("[0].volumeQuantidade", equalTo(3))
                .body("[0].melhorCompra", equalTo("9.99")).body("[0].melhorVenda", equalTo("10.02"))
                .body("[0].spread", equalTo("0.03")).body("[0].defasado", equalTo(false));
        given().auth().oauth2(token("mercado:leitura")).get("/api/v1/ativos/ALFA3/livro").then().statusCode(200)
                .body("origem", equalTo("MATCHING")).body("compra.niveis[0].quantidade", equalTo(7))
                .body("compra.niveis[0].ofertas", equalTo(2)).body("revisao", equalTo(5))
                .body("conta", nullValue()).body("ordem", nullValue());
    }

    @Test
    void ausenciaDefasagemEIdadeSemNegocioSaoDistintas() {
        given().auth().oauth2(token("mercado:leitura")).get("/api/v1/ativos/ALFA3/livro").then()
                .body("estadoLivro", equalTo("SEM_DADOS")).body("origem", equalTo("MATCHING"));
        preparar(HOJE, false, Instant.parse("2020-01-01T00:00:00Z"));
        given().auth().oauth2(token("mercado:leitura")).get("/api/v1/ativos").then()
                .body("[0].defasado", equalTo(false));
        situacao.definir(false);
        given().auth().oauth2(token("mercado:leitura")).get("/api/v1/ativos").then()
                .body("[0].defasado", equalTo(true));
        situacao.definir(true); preparar(HOJE.minusDays(1), false, Instant.now());
        given().auth().oauth2(token("mercado:leitura")).get("/api/v1/ativos").then()
                .body("[0].defasado", equalTo(true));
        preparar(HOJE, true, Instant.now());
        given().auth().oauth2(token("mercado:leitura")).get("/api/v1/ativos").then()
                .body("[0].defasado", equalTo(true));
    }

    @Test
    void ofertaSemNegocioAtualizaLivroComIndicadoresAusentes() {
        var vazio = new LadoCotado(List.of(), 0, 0);
        var compra = new LadoCotado(List.of(new NivelCotado(new BigDecimal("9.99"), 7, 1, 7)), 1, 7);
        cotacoes.preparar(new LivroCotado(new SessaoCotacao("ALFA3", HOJE, null, 0,
                new BigDecimal("0.00"), 1, Instant.now(), false), compra, vazio));
        given().auth().oauth2(token("mercado:leitura")).get("/api/v1/ativos").then().statusCode(200)
                .body("[0].origem", equalTo("MATCHING")).body("[0].estadoLivro", equalTo("SOMENTE_COMPRA"))
                .body("[0].melhorCompra", equalTo("9.99")).body("[0].ultimoPreco", nullValue())
                .body("[0].vwap", nullValue()).body("[0].volumeQuantidade", nullValue())
                .body("[0].volumeFinanceiro", nullValue());
    }

    @Test
    void principalRecusaPreparacaoSinteticaEUuidNaoEnxergaReal() {
        preparar(HOJE, false, Instant.now());
        given().auth().oauth2(token("mercado:preparacao")).contentType("application/json")
                .body(Map.of("sessao", HOJE.toString(), "ofertas", List.of(), "negocios", List.of()))
                .put("/api/v1/preparacoes-mercado/" + PRINCIPAL + "/ativos/ALFA3").then()
                .statusCode(409).body("codigo", equalTo("NAMESPACE_REAL"));
        given().auth().oauth2(token("mercado:preparacao"))
                .delete("/api/v1/preparacoes-mercado/" + PRINCIPAL).then()
                .statusCode(409).body("codigo", equalTo("NAMESPACE_REAL"));
        given().auth().oauth2(token("mercado:leitura")).queryParam("namespace", UUID.randomUUID())
                .get("/api/v1/ativos").then().statusCode(200)
                .body("[0].estadoLivro", equalTo("SEM_DADOS")).body("[0].ultimoPreco", nullValue());
    }
}
