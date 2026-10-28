package br.prospero.bo.razao;

import br.prospero.bo.posicoes.dominio.EventoMatching;
import br.prospero.bo.posicoes.dominio.SituacaoPosicoes;
import br.prospero.bo.razao.dominio.*;
import br.prospero.bo.razao.helper.CursorRazao;
import br.prospero.bo.razao.helper.PlanoDeContas;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(RazaoResourceTest.Perfil.class)
class RazaoResourceTest {
    private static final String CHAVE = "chave-sintetica-de-teste-com-mais-de-32-bytes";
    private static final UUID ORDEM_002 = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID ORDEM_003 = UUID.fromString("00000000-0000-4000-8000-000000000003");
    @Inject Fonte fonte;
    @Inject Situacao situacao;

    public static class Perfil implements QuarkusTestProfile {
        @Override public Set<Class<?>> getEnabledAlternatives() { return Set.of(Fonte.class, Situacao.class); }
    }

    /** Fonte em memória com o mesmo plano de contas e filtro por conta da implementação JPA. */
    @ApplicationScoped @Alternative
    public static class Fonte implements ConsultaRazao {
        private final List<ParticipacaoNegocio> participacoes = new ArrayList<>();
        private final List<LancamentoRazao> lancamentos = new ArrayList<>();
        private final List<String> contasConsultadas = new ArrayList<>();
        private boolean falhar;

        public synchronized void reiniciar() {
            participacoes.clear(); lancamentos.clear(); contasConsultadas.clear(); falhar = false;
            registrar("ALFA3-2026-10-23-1", "20.00", 100, "CONTA-002", "CONTA-003");
            registrar("ALFA3-2026-10-23-2", "21.00", 3, "CONTA-003", "CONTA-002");
            registrar("DELTA3-2026-10-23-1", "40.00", 1, "CONTA-002", "CONTA-002");
        }

        public synchronized void falharNaLeitura() { falhar = true; }

        private void registrar(String id, String preco, long quantidade, String compra, String venda) {
            var fato = new FatoNegocio(id, id.substring(0, id.indexOf('-')), LocalDate.of(2026, 10, 23),
                    LocalDate.of(2026, 10, 27), new BigDecimal(preco), quantidade,
                    new EventoMatching.Alvo(compra.equals("CONTA-002") ? ORDEM_002 : ORDEM_003, UUID.randomUUID(), compra),
                    new EventoMatching.Alvo(UUID.randomUUID(), UUID.randomUUID(), venda));
            for (TransacaoContabil t : PlanoDeContas.d0(fato)) {
                UUID ordem = t.lado() == LadoNegocio.COMPRADOR ? fato.compra().ordem() : fato.venda().ordem();
                participacoes.add(new ParticipacaoNegocio(participacoes.size() + 1, id, fato.ticker(), t.lado(), ordem,
                        fato.sessao(), fato.liquidacao(), fato.preco(), quantidade, fato.valor(), EstadoLiquidacao.PENDENTE));
                for (Partida p : t.partidas()) {
                    lancamentos.add(new LancamentoRazao(lancamentos.size() + 1, t.id(), id, fato.ticker(), t.lado(),
                            t.momento(), fato.sessao(), p));
                }
                contas.put(participacoes.size(), t.conta());
                for (int i = lancamentos.size() - t.partidas().size(); i < lancamentos.size(); i++) contasLancamento.put(i + 1, t.conta());
            }
        }

        private final Map<Integer, String> contas = new HashMap<>();
        private final Map<Integer, String> contasLancamento = new HashMap<>();

        @Override public synchronized List<ParticipacaoNegocio> negocios(String conta, long antesDe, int limite) {
            contasConsultadas.add(conta);
            if (falhar) throw new jakarta.persistence.PersistenceException("indisponivel");
            return participacoes.reversed().stream().filter(p -> contas.get((int) p.sequencia()).equals(conta))
                    .filter(p -> p.sequencia() < antesDe).limit(limite).toList();
        }

        @Override public synchronized List<ParticipacaoNegocio> negocio(String conta, String negocio) {
            contasConsultadas.add(conta);
            return participacoes.stream().filter(p -> p.negocio().equals(negocio))
                    .filter(p -> contas.get((int) p.sequencia()).equals(conta)).toList();
        }

        @Override public synchronized List<LancamentoRazao> lancamentos(String conta, long antesDe, int limite) {
            contasConsultadas.add(conta);
            if (falhar) throw new jakarta.persistence.PersistenceException("indisponivel");
            return lancamentos.reversed().stream().filter(l -> contasLancamento.get((int) l.sequencia()).equals(conta))
                    .filter(l -> l.sequencia() < antesDe).limit(limite).toList();
        }

        public synchronized List<String> contasConsultadas() { return List.copyOf(contasConsultadas); }
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
    void negociosDaContaDoTokenMaisRecentesPrimeiroSemContraparte() {
        ler("CONTA-002", "/api/v1/negocios").statusCode(200)
                .header("Cache-Control", containsString("no-store"))
                .body("conta", equalTo("CONTA-002")).body("negocios", hasSize(4))
                .body("negocios.lado", contains("VENDEDOR", "COMPRADOR", "VENDEDOR", "COMPRADOR"))
                .body("negocios.negocio", contains("DELTA3-2026-10-23-1", "DELTA3-2026-10-23-1",
                        "ALFA3-2026-10-23-2", "ALFA3-2026-10-23-1"))
                .body("negocios[3].ticker", equalTo("ALFA3")).body("negocios[3].ordem", equalTo(ORDEM_002.toString()))
                .body("negocios[3].preco", equalTo("20.00")).body("negocios[3].quantidade", equalTo("100"))
                .body("negocios[3].valor", equalTo("2000.00")).body("negocios[3].dataSessao", equalTo("2026-10-23"))
                .body("negocios[3].dataLiquidacao", equalTo("2026-10-27"))
                .body("negocios[3].liquidacao", equalTo("PENDENTE"))
                .body("negocios[3]", not(hasKey("contraparte"))).body("negocios[3]", not(hasKey("contaVenda")))
                .body("proximoCursor", nullValue())
                .body(not(containsString("CONTA-003")));
    }

    @Test
    void razaoMostraPartidasDaPropriaContaContraACamaraSemRevelarOutraConta() {
        var resposta = ler("CONTA-003", "/api/v1/razao?limite=100").statusCode(200)
                .header("Cache-Control", containsString("no-store"))
                .body("conta", equalTo("CONTA-003")).body("lancamentos", hasSize(8))
                .body(not(containsString("CONTA-002")));
        // Mais antigo por último: vendedor de 100 ALFA3 × 20,00 (ADR-016).
        resposta.body("lancamentos[4..7].contaContabil",
                        contains("CAMARA", "CAIXA_A_RECEBER", "CUSTODIA", "CAMARA"))
                .body("lancamentos[4..7].natureza", contains("CREDITO", "DEBITO", "CREDITO", "DEBITO"))
                .body("lancamentos[4..7].unidade", contains("BRL", "BRL", "ALFA3", "ALFA3"))
                .body("lancamentos[4..7].valor", contains("2000.00", "2000.00", "100", "100"))
                .body("lancamentos[7].negocio", equalTo("ALFA3-2026-10-23-1"))
                .body("lancamentos[7].lado", equalTo("VENDEDOR")).body("lancamentos[7].momento", equalTo("D0"))
                .body("lancamentos[7].data", equalTo("2026-10-23"))
                .body("lancamentos[7].transacao", matchesPattern("[0-9a-f-]{36}"));
    }

    @Test
    void contaDoTokenNaoESubstituidaPorQueryOuCabecalho() {
        given().auth().oauth2(token("CONTA-003", "razao:leitura")).queryParam("conta", "CONTA-002")
                .header("X-Conta", "CONTA-002").get("/api/v1/razao").then().statusCode(200)
                .body("conta", equalTo("CONTA-003"));
        given().auth().oauth2(token("CONTA-003", "razao:leitura")).queryParam("conta", "CONTA-002")
                .get("/api/v1/negocios").then().statusCode(200).body("conta", equalTo("CONTA-003"));
        assertEquals(List.of("CONTA-003", "CONTA-003"), fonte.contasConsultadas());
    }

    @Test
    void paginaComCursorOpacoAteOFimELimiteMaximoCem() {
        String cursor = ler("CONTA-002", "/api/v1/negocios?limite=3").statusCode(200)
                .body("negocios", hasSize(3)).body("proximoCursor", matchesPattern("[A-Za-z0-9_-]{1,120}"))
                .extract().path("proximoCursor");
        ler("CONTA-002", "/api/v1/negocios?limite=3&cursor=" + cursor).statusCode(200)
                .body("negocios", hasSize(1)).body("negocios[0].negocio", equalTo("ALFA3-2026-10-23-1"))
                .body("proximoCursor", nullValue());
        String razao = ler("CONTA-002", "/api/v1/razao?limite=5").statusCode(200).body("lancamentos", hasSize(5))
                .extract().path("proximoCursor");
        ler("CONTA-002", "/api/v1/razao?limite=100&cursor=" + razao).statusCode(200).body("lancamentos", hasSize(11));
        ler("CONTA-002", "/api/v1/razao?limite=100").statusCode(200).body("lancamentos", hasSize(16));
        ler("CONTA-002", "/api/v1/razao").statusCode(200).body("lancamentos", hasSize(16));
    }

    @Test
    void cursorAdulteradoDeOutraContaOuDeOutraListagemELimiteForaDaFaixaSao400Genericos() {
        String deOutraConta = CursorRazao.codificar("razao", "CONTA-003", 9);
        String deOutraListagem = CursorRazao.codificar("negocios", "CONTA-002", 9);
        String valido = CursorRazao.codificar("razao", "CONTA-002", 9);
        String claro = new String(Base64.getUrlDecoder().decode(valido), java.nio.charset.StandardCharsets.UTF_8);
        char ultimo = claro.charAt(claro.length() - 1);
        String adulterado = Base64.getUrlEncoder().withoutPadding().encodeToString((claro.substring(0, claro.length() - 1)
                + (ultimo == '0' ? '1' : '0')).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<String> invalidos = List.of("/api/v1/razao?cursor=" + deOutraConta, "/api/v1/razao?cursor=" + deOutraListagem,
                "/api/v1/razao?cursor=" + adulterado, "/api/v1/razao?cursor=" + "A".repeat(121),
                "/api/v1/razao?limite=0", "/api/v1/razao?limite=101", "/api/v1/razao?limite=-1",
                "/api/v1/razao?limite=abc", "/api/v1/razao?limite=1000", "/api/v1/negocios?limite=101",
                "/api/v1/negocios?cursor=" + valido, "/api/v1/negocios?limite=1x");
        for (String caminho : invalidos) {
            ler("CONTA-002", caminho).statusCode(400).contentType(startsWith("application/problem+json"))
                    .header("Cache-Control", containsString("no-store"))
                    .body("codigo", equalTo("CONSULTA_INVALIDA")).body("detail", equalTo("Cursor ou limite inválido."))
                    .body(not(containsString(deOutraConta))).body(not(containsString("CONTA-003")));
        }
        ler("CONTA-002", "/api/v1/razao?cursor=" + valido).statusCode(200);
    }

    @Test
    void negocioDeQueAContaNaoParticipaEIdenticoAoInexistente() {
        ler("CONTA-003", "/api/v1/negocios/ALFA3-2026-10-23-1").statusCode(200)
                .header("Cache-Control", containsString("no-store"))
                .body("conta", equalTo("CONTA-003")).body("negocio", equalTo("ALFA3-2026-10-23-1"))
                .body("valor", equalTo("2000.00")).body("dataLiquidacao", equalTo("2026-10-27"))
                .body("lados", hasSize(1)).body("lados[0].lado", equalTo("VENDEDOR"))
                .body("lados[0].liquidacao", equalTo("PENDENTE")).body(not(containsString("CONTA-002")))
                .body(not(containsString(ORDEM_002.toString())));
        ler("CONTA-002", "/api/v1/negocios/DELTA3-2026-10-23-1").statusCode(200)
                .body("lados.lado", contains("COMPRADOR", "VENDEDOR"));
        Map<String, Object> naoParticipa = problema404("CONTA-003", "/api/v1/negocios/DELTA3-2026-10-23-1");
        Map<String, Object> inexistente = problema404("CONTA-003", "/api/v1/negocios/DELTA3-2026-10-23-9");
        Map<String, Object> malformado = problema404("CONTA-003", "/api/v1/negocios/nao-e-um-negocio");
        for (Map<String, Object> outro : List.of(inexistente, malformado)) {
            for (String campo : List.of("type", "title", "status", "codigo", "detail")) {
                assertEquals(naoParticipa.get(campo), outro.get(campo), campo);
            }
        }
        assertEquals("NEGOCIO_NAO_ENCONTRADO", naoParticipa.get("codigo"));
    }

    @Test
    void contaDeMedicao005LeRazaoComoAsDemais() {
        ler("CONTA-005", "/api/v1/razao").statusCode(200).body("conta", equalTo("CONTA-005"));
        ler("CONTA-999", "/api/v1/razao").statusCode(403);
    }

    @Test
    void negaSemTokenComEscopoDePosicoesAudienciaErradaEContaDesconhecida() {
        for (String rota : List.of("/api/v1/negocios", "/api/v1/razao", "/api/v1/negocios/ALFA3-2026-10-23-1")) {
            given().get(rota).then().statusCode(401).header("Cache-Control", containsString("no-store"));
            given().auth().oauth2(token("CONTA-002", "posicoes:leitura")).get(rota).then().statusCode(403);
            given().auth().oauth2(token("CONTA-999", "razao:leitura")).get(rota).then().statusCode(403);
            String audienciaFo = Jwt.issuer("prospero-local").audience("prospero-front-office").subject("CONTA-002")
                    .groups(Set.of("razao:leitura")).expiresIn(300).signWithSecret(CHAVE);
            String expirado = Jwt.issuer("prospero-local").audience("prospero-back-office").subject("CONTA-002")
                    .groups(Set.of("razao:leitura")).issuedAt(Instant.now().minusSeconds(7200))
                    .expiresAt(Instant.now().minusSeconds(3600)).signWithSecret(CHAVE);
            given().auth().oauth2(audienciaFo).get(rota).then().statusCode(401);
            given().auth().oauth2(expirado).get(rota).then().statusCode(401);
        }
        given().auth().oauth2(token("CONTA-002", "razao:leitura")).post("/api/v1/razao").then().statusCode(405);
    }

    @Test
    void bootstrapQuarentenaOuFalhaDeLeituraRespondem503SemHistoricoParcial() {
        situacao.definir(false);
        for (String rota : List.of("/api/v1/negocios", "/api/v1/razao", "/api/v1/negocios/ALFA3-2026-10-23-1")) {
            ler("CONTA-002", rota).statusCode(503).contentType(startsWith("application/problem+json"))
                    .header("Cache-Control", containsString("no-store"))
                    .body("codigo", equalTo("RAZAO_INDISPONIVEL")).body("$", not(hasKey("negocios")));
        }
        situacao.perderDuranteConsulta();
        ler("CONTA-002", "/api/v1/razao").statusCode(503).body("$", not(hasKey("lancamentos")));
        situacao.perderDuranteConsulta();
        ler("CONTA-002", "/api/v1/negocios").statusCode(503);
        situacao.definir(true);
        fonte.falharNaLeitura();
        ler("CONTA-002", "/api/v1/razao").statusCode(503).body("codigo", equalTo("RAZAO_INDISPONIVEL"));
    }

    @Test
    void contratoOpenApiDocumentaAsConsultas() {
        given().get("/q/openapi?format=json").then().statusCode(200)
                .body("paths.'/api/v1/negocios'.get.responses", hasKey("400"))
                .body("paths.'/api/v1/negocios/{id}'.get.responses", hasKey("404"))
                .body("paths.'/api/v1/razao'.get.responses", hasKey("503"));
    }

    private static ValidatableResponse ler(String conta, String caminho) {
        return given().auth().oauth2(token(conta, "razao:leitura")).urlEncodingEnabled(false).get(caminho).then();
    }

    private static Map<String, Object> problema404(String conta, String caminho) {
        return ler(conta, caminho).statusCode(404).contentType(startsWith("application/problem+json"))
                .header("Cache-Control", containsString("no-store")).extract().jsonPath().getMap("$");
    }

    private static String token(String conta, String... grupos) {
        return Jwt.issuer("prospero-local").audience("prospero-back-office").subject(conta).groups(Set.of(grupos))
                .expiresIn(300).signWithSecret(CHAVE);
    }
}
