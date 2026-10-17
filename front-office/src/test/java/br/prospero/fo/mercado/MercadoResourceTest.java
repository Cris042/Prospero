package br.prospero.fo.mercado;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class MercadoResourceTest {
    private static final String CHAVE = "chave-sintetica-de-teste-com-mais-de-32-bytes";
    private static final String HOJE = LocalDate.now(ZoneId.of("America/Sao_Paulo")).toString();

    @Inject
    RepositorioMercadoMemoria repositorio;

    @BeforeEach
    void limpar() {
        repositorio.limparTudo();
    }

    private static String token(String... grupos) {
        return Jwt.issuer("prospero-local").audience("prospero-front-office").groups(Set.of(grupos))
                .expiresIn(300).signWithSecret(CHAVE);
    }

    private static String leitura() {
        return token("mercado:leitura");
    }

    private static String preparo() {
        return token("mercado:preparacao");
    }

    private static Map<String, Object> snapshotCompleto() {
        return Map.of("sessao", HOJE,
                "ofertas", java.util.List.of(
                        Map.of("lado", "COMPRA", "preco", "9.98", "quantidade", 100),
                        Map.of("lado", "COMPRA", "preco", "9.98", "quantidade", 50),
                        Map.of("lado", "COMPRA", "preco", "9.95", "quantidade", 200),
                        Map.of("lado", "VENDA", "preco", "10.02", "quantidade", 300)),
                "negocios", java.util.List.of(Map.of("preco", "10.00", "quantidade", 100),
                        Map.of("preco", "10.01", "quantidade", 50)));
    }

    private static void preparar(UUID namespace, String ticker, Object corpo, int status) {
        given().auth().oauth2(preparo()).contentType(ContentType.JSON).body(corpo)
                .when().put("/api/v1/preparacoes-mercado/" + namespace + "/ativos/" + ticker)
                .then().statusCode(status);
    }

    @Test
    void listaCincoAtivosSemDadosComoAusentesENaoZero() {
        given().auth().oauth2(leitura()).when().get("/api/v1/ativos").then()
                .statusCode(200).header("Cache-Control", "no-store")
                .body("ticker", contains("ALFA3", "BETA3", "GAMA3", "DELTA3", "OMEGA3"))
                .body("[0].precoReferencia", equalTo("10.00"))
                .body("[0].tick", equalTo("0.01"))
                .body("[0].lote", equalTo(1))
                .body("[0].estadoLivro", equalTo("SEM_DADOS"))
                .body("[0].ultimoPreco", nullValue())
                .body("[0].volumeQuantidade", nullValue())
                .body("[0].origem", nullValue());
    }

    @Test
    void preparaECalculaIndicadoresELivroAgregado() {
        UUID ns = UUID.randomUUID();
        preparar(ns, "ALFA3", snapshotCompleto(), 200);
        given().auth().oauth2(leitura()).queryParam("namespace", ns.toString()).when().get("/api/v1/ativos").then()
                .statusCode(200)
                .body("[0].ultimoPreco", equalTo("10.01"))
                .body("[0].variacao", equalTo("0.01"))
                .body("[0].variacaoPercentual", equalTo("0.10"))
                .body("[0].melhorCompra", equalTo("9.98"))
                .body("[0].melhorVenda", equalTo("10.02"))
                .body("[0].spread", equalTo("0.04"))
                .body("[0].volumeQuantidade", equalTo(150))
                .body("[0].volumeFinanceiro", equalTo("1500.50"))
                .body("[0].estadoLivro", equalTo("COMPLETO"))
                .body("[0].revisao", equalTo(1))
                .body("[0].origem", equalTo("PREPARACAO_SINTETICA"))
                .body("[0].defasado", equalTo(false))
                .body("[1].estadoLivro", equalTo("SEM_DADOS"));
        given().auth().oauth2(leitura()).queryParam("namespace", ns.toString()).when().get("/api/v1/ativos/ALFA3/livro").then()
                .statusCode(200)
                .body("compra.niveis.preco", contains("9.98", "9.95"))
                .body("compra.niveis.quantidade", contains(150, 200))
                .body("compra.niveis.ofertas", contains(2, 1))
                .body("compra.niveis.quantidadeAcumulada", contains(150, 350))
                .body("compra.niveisTotal", equalTo(2))
                .body("venda.niveis", hasSize(1))
                .body("venda.quantidadeTotal", equalTo(300));
    }

    @Test
    void namespacesSaoIsoladosELimpezaEIdempotente() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        preparar(a, "BETA3", Map.of("sessao", HOJE, "ofertas", java.util.List.of(), "negocios", java.util.List.of()), 200);
        preparar(a, "BETA3", Map.of("sessao", HOJE, "ofertas", java.util.List.of(), "negocios", java.util.List.of()), 200);
        given().auth().oauth2(leitura()).queryParam("namespace", a.toString()).when().get("/api/v1/ativos/BETA3/livro")
                .then().body("estadoLivro", equalTo("VAZIO")).body("revisao", equalTo(2));
        given().auth().oauth2(leitura()).queryParam("namespace", b.toString()).when().get("/api/v1/ativos/BETA3/livro")
                .then().body("estadoLivro", equalTo("SEM_DADOS")).body("revisao", nullValue())
                .body("compra", nullValue()).body("venda", nullValue()).body("origem", nullValue());
        for (int i = 0; i < 2; i++) {
            given().auth().oauth2(preparo()).when().delete("/api/v1/preparacoes-mercado/" + a).then().statusCode(204);
        }
        given().auth().oauth2(leitura()).queryParam("namespace", a.toString()).when().get("/api/v1/ativos/BETA3/livro")
                .then().body("estadoLivro", equalTo("SEM_DADOS"));
    }

    @Test
    void recusasNaoEscrevemParcialmente() {
        UUID ns = UUID.randomUUID();
        preparar(ns, "ALFA3", snapshotCompleto(), 200);
        Map<String, Object> cruzado = Map.of("sessao", HOJE, "negocios", java.util.List.of(), "ofertas", java.util.List.of(
                Map.of("lado", "COMPRA", "preco", "10.05", "quantidade", 1),
                Map.of("lado", "VENDA", "preco", "10.05", "quantidade", 1)));
        given().auth().oauth2(preparo()).contentType(ContentType.JSON).body(cruzado)
                .when().put("/api/v1/preparacoes-mercado/" + ns + "/ativos/ALFA3").then()
                .statusCode(422).contentType("application/problem+json").body("codigo", equalTo("LIVRO_CRUZADO"));
        given().auth().oauth2(leitura()).queryParam("namespace", ns.toString()).when().get("/api/v1/ativos/ALFA3/livro")
                .then().body("revisao", equalTo(1)).body("estadoLivro", equalTo("COMPLETO"));
    }

    @Test
    void codigosDeErroDeEntrada() {
        UUID ns = UUID.randomUUID();
        Map<String, Object> foraTick = Map.of("sessao", HOJE, "negocios", java.util.List.of(),
                "ofertas", java.util.List.of(Map.of("lado", "VENDA", "preco", "10.001", "quantidade", 1)));
        given().auth().oauth2(preparo()).contentType(ContentType.JSON).body(foraTick)
                .when().put("/api/v1/preparacoes-mercado/" + ns + "/ativos/ALFA3").then()
                .statusCode(422).body("codigo", equalTo("PRECO_INVALIDO"));
        given().auth().oauth2(preparo()).contentType(ContentType.JSON)
                .body("{\"sessao\":\"" + HOJE + "\",\"ofertas\":[],\"negocios\":[],\"extra\":1}")
                .when().put("/api/v1/preparacoes-mercado/" + ns + "/ativos/ALFA3").then()
                .statusCode(400).body("codigo", equalTo("JSON_INVALIDO"));
        given().auth().oauth2(preparo()).contentType(ContentType.JSON).body("{\"sessao\":\"" + HOJE + "\"}")
                .when().put("/api/v1/preparacoes-mercado/" + ns + "/ativos/ALFA3").then()
                .statusCode(400).body("codigo", equalTo("CORPO_INVALIDO"));
        given().auth().oauth2(preparo()).contentType(ContentType.JSON)
                .body(Map.of("sessao", LocalDate.parse(HOJE).plusDays(1).toString(), "ofertas", java.util.List.of(), "negocios", java.util.List.of()))
                .when().put("/api/v1/preparacoes-mercado/" + ns + "/ativos/ALFA3").then()
                .statusCode(422).body("codigo", equalTo("SESSAO_FUTURA"));
        given().auth().oauth2(preparo()).contentType(ContentType.JSON).body(snapshotCompleto())
                .when().put("/api/v1/preparacoes-mercado/nao-uuid/ativos/ALFA3").then()
                .statusCode(400).body("codigo", equalTo("NAMESPACE_INVALIDO"));
        given().auth().oauth2(preparo()).contentType(ContentType.JSON).body(snapshotCompleto())
                .when().put("/api/v1/preparacoes-mercado/" + ns + "/ativos/ZETA3").then()
                .statusCode(404).body("codigo", equalTo("ATIVO_DESCONHECIDO"));
        given().auth().oauth2(leitura()).when().get("/api/v1/ativos/alfa3/livro").then().statusCode(404);
        given().auth().oauth2(leitura()).queryParam("namespace", "X").when().get("/api/v1/ativos").then()
                .statusCode(400).body("codigo", equalTo("NAMESPACE_INVALIDO"));
    }

    @Test
    void limitaQuantidadeDeNamespaces() {
        for (int i = 0; i < 3; i++) {
            preparar(UUID.randomUUID(), "ALFA3", snapshotCompleto(), 200);
        }
        given().auth().oauth2(preparo()).contentType(ContentType.JSON).body(snapshotCompleto())
                .when().put("/api/v1/preparacoes-mercado/" + UUID.randomUUID() + "/ativos/ALFA3").then()
                .statusCode(429).body("codigo", equalTo("LIMITE_NAMESPACES"));
    }

    @Test
    void exigeTokenValidoEEscopoCorreto() {
        given().when().get("/api/v1/ativos").then().statusCode(401);
        given().auth().oauth2("abc.def.ghi").when().get("/api/v1/ativos").then().statusCode(401);
        String outraChave = Jwt.issuer("prospero-local").audience("prospero-front-office").groups("mercado:leitura")
                .expiresIn(300).signWithSecret("outra-chave-sintetica-com-mais-de-32-bytes");
        given().auth().oauth2(outraChave).when().get("/api/v1/ativos").then().statusCode(401);
        String outroEmissor = Jwt.issuer("intruso").audience("prospero-front-office").groups("mercado:leitura")
                .expiresIn(300).signWithSecret(CHAVE);
        given().auth().oauth2(outroEmissor).when().get("/api/v1/ativos").then().statusCode(401);
        String outraAudiencia = Jwt.issuer("prospero-local").audience("prospero-carga").groups("mercado:leitura")
                .expiresIn(300).signWithSecret(CHAVE);
        given().auth().oauth2(outraAudiencia).when().get("/api/v1/ativos").then().statusCode(401);
        String expirado = Jwt.issuer("prospero-local").audience("prospero-front-office").groups("mercado:leitura")
                .issuedAt(1_000_000L).expiresAt(1_000_300L).signWithSecret(CHAVE);
        given().auth().oauth2(expirado).when().get("/api/v1/ativos").then().statusCode(401);
        String semAssinatura = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0."
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"iss\":\"prospero-local\",\"aud\":\"prospero-front-office\",\"groups\":[\"mercado:leitura\"],\"exp\":"
                        + (System.currentTimeMillis() / 1000 + 300) + "}").getBytes()) + ".";
        given().auth().oauth2(semAssinatura).when().get("/api/v1/ativos").then().statusCode(401);
        given().auth().oauth2(preparo()).when().get("/api/v1/ativos").then().statusCode(403);
        given().auth().oauth2(leitura()).contentType(ContentType.JSON).body(snapshotCompleto())
                .when().put("/api/v1/preparacoes-mercado/" + UUID.randomUUID() + "/ativos/ALFA3").then().statusCode(403);
        given().auth().oauth2(leitura()).when().delete("/api/v1/preparacoes-mercado/" + UUID.randomUUID()).then().statusCode(403);
    }

    @Test
    void recusaCoercoesSilenciosasDeTipo() {
        UUID ns = UUID.randomUUID();
        for (String corpo : new String[]{
                "{\"sessao\":\"" + HOJE + "\",\"negocios\":[],\"ofertas\":[{\"lado\":\"VENDA\",\"preco\":\"10.10\",\"quantidade\":1.5}]}",
                "{\"sessao\":\"" + HOJE + "\",\"negocios\":[],\"ofertas\":[{\"lado\":\"VENDA\",\"preco\":\"10.10\",\"quantidade\":\"100\"}]}",
                "{\"sessao\":\"" + HOJE + "\",\"negocios\":[],\"ofertas\":[{\"lado\":\"VENDA\",\"preco\":10.5,\"quantidade\":1}]}",
                "{\"sessao\":\"" + HOJE + "\",\"negocios\":[{\"preco\":\"10.10\",\"quantidade\":1.0}],\"ofertas\":[]}",
                "{\"sessao\":"}) {
            given().auth().oauth2(preparo()).contentType(ContentType.JSON).body(corpo)
                    .when().put("/api/v1/preparacoes-mercado/" + ns + "/ativos/ALFA3").then()
                    .statusCode(400).contentType("application/problem+json").body("codigo", equalTo("JSON_INVALIDO"));
        }
        given().auth().oauth2(leitura()).queryParam("namespace", ns.toString()).when().get("/api/v1/ativos/ALFA3/livro")
                .then().body("estadoLivro", equalTo("SEM_DADOS"));
    }

    @Test
    void problemDetailsTrazOcorrenciaCorrelacaoEAutenticacao() {
        given().header("X-Request-ID", "req-12345678").when().get("/api/v1/ativos").then()
                .statusCode(401).contentType("application/problem+json").header("WWW-Authenticate", "Bearer")
                .body("codigo", equalTo("AUTENTICACAO_INVALIDA")).body("instance", equalTo("/api/v1/ativos"))
                .body("correlacao", equalTo("req-12345678"));
        given().auth().oauth2(preparo()).when().get("/api/v1/ativos").then()
                .statusCode(403).contentType("application/problem+json").body("codigo", equalTo("PERMISSAO_INSUFICIENTE"));
        given().auth().oauth2(leitura()).header("X-Request-ID", "<script>").queryParam("namespace", "x")
                .when().get("/api/v1/ativos").then().statusCode(400)
                .body("correlacao", org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}"));
        given().auth().oauth2(preparo()).contentType(ContentType.TEXT).body("x")
                .when().put("/api/v1/preparacoes-mercado/" + UUID.randomUUID() + "/ativos/ALFA3").then()
                .statusCode(415).contentType("application/problem+json");
    }

    @Test
    void sessaoAnteriorFicaDefasadaEExcessoDeOfertasE422() {
        UUID ns = UUID.randomUUID();
        preparar(ns, "ALFA3", Map.of("sessao", "2020-01-02", "ofertas", java.util.List.of(), "negocios", java.util.List.of()), 200);
        given().auth().oauth2(leitura()).queryParam("namespace", ns.toString()).when().get("/api/v1/ativos/ALFA3/livro")
                .then().body("defasado", equalTo(true)).body("sessao", equalTo("2020-01-02"))
                .body("origem", equalTo("PREPARACAO_SINTETICA"));
        java.util.List<Map<String, Object>> muitas = new java.util.ArrayList<>();
        for (int i = 0; i < 201; i++) {
            muitas.add(Map.of("lado", i % 2 == 0 ? "COMPRA" : "VENDA", "preco", i % 2 == 0 ? "9.00" : "11.00", "quantidade", 1));
        }
        given().auth().oauth2(preparo()).contentType(ContentType.JSON)
                .body(Map.of("sessao", HOJE, "ofertas", muitas, "negocios", java.util.List.of()))
                .when().put("/api/v1/preparacoes-mercado/" + ns + "/ativos/ALFA3").then()
                .statusCode(422).body("codigo", equalTo("LIMITE_EXCEDIDO"));
    }

    @Test
    void sistemaContinuaPublico() {
        given().when().get("/api/v1/sistema").then().statusCode(200);
    }
}
