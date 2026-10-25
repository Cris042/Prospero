package br.prospero.fo.ordens;

import br.prospero.fo.ordens.dominio.EstadoOrdem;
import br.prospero.fo.ordens.dominio.MatchingIncoerenteException;
import br.prospero.fo.ordens.dominio.ResultadoRisco;
import br.prospero.fo.ordens.dominio.Ordem;
import br.prospero.fo.ordens.dominio.EventoContabilizacao;
import br.prospero.fo.ordens.dominio.LadoOrdem;
import br.prospero.fo.ordens.dominio.TipoOrdem;
import br.prospero.fo.ordens.dominio.Validade;
import br.prospero.fo.comum.dominio.MensagemSaida;
import br.prospero.fo.ordens.jpa.RepositorioOrdensJpa;
import br.prospero.fo.ordens.jpa.RepositorioTrajetoriaJpa;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;

import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.List;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/** Opt-in; somente banco descartável indicado por MATCHING_TEST_DB_*. */
@QuarkusTest
@TestProfile(TrajetoriaPostgresIT.Banco.class)
class TrajetoriaPostgresIT {
    private static final String SCHEMA = "fo_trajetoria_" + UUID.randomUUID().toString().replace("-", "");

    public static class Banco implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            try (var c = conexao(); var s = c.createStatement()) { s.execute("CREATE SCHEMA " + SCHEMA); }
            catch (Exception erro) { throw new IllegalStateException("Banco descartável obrigatório", erro); }
            return Map.of("quarkus.datasource.active", "true", "quarkus.hibernate-orm.active", "true",
                    "quarkus.datasource.jdbc.url", System.getenv("MATCHING_TEST_DB_URL") + "?currentSchema=" + SCHEMA,
                    "quarkus.datasource.username", System.getenv("MATCHING_TEST_DB_USER"),
                    "quarkus.datasource.password", System.getenv("MATCHING_TEST_DB_PASSWORD"),
                    "quarkus.flyway.migrate-at-start", "true", "quarkus.flyway.schemas", SCHEMA);
        }
    }

    private static java.sql.Connection conexao() throws Exception {
        return DriverManager.getConnection(System.getenv("MATCHING_TEST_DB_URL"),
                System.getenv("MATCHING_TEST_DB_USER"), System.getenv("MATCHING_TEST_DB_PASSWORD"));
    }

    @AfterAll
    static void removerSchema() throws Exception {
        try (var c = conexao(); var s = c.createStatement()) { s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE"); }
    }

    @Inject RepositorioTrajetoriaJpa trajetoria;
    @Inject RepositorioOrdensJpa ordens;
    @Inject EntityManager em;

    private UUID criar() {
        return criar("CONTA-002", "COMPRA");
    }

    private UUID criar(String conta, String lado) {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
                INSERT INTO ordem(id,conta,ticker,lado,tipo,validade,quantidade,preco_limite,grupo_envio,
                    estado,data_sessao,recebida_em)
                VALUES(:i,:c,'ALFA3',:l,'LIMIT','DIA',10,10,:i,'RECEBIDA','2026-10-25',now())
                """).setParameter("i", id).setParameter("c", conta).setParameter("l", lado).executeUpdate());
        return id;
    }

    private EventoContabilizacao contabilizacao(UUID compra, UUID venda, String contaVenda, long quantidade) {
        String negocio = "ALFA3-2026-10-25-" + UUID.randomUUID();
        UUID id = UUID.nameUUIDFromBytes(("acompanhamento:" + negocio).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new EventoContabilizacao(id, UUID.randomUUID(), negocio, "ALFA3", quantidade, compra, "CONTA-002",
                venda, contaVenda, Instant.parse("2026-10-25T10:05:00Z"), null, "e".repeat(64));
    }

    private long revisao(String conta) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT revisao FROM trajetoria_revisao WHERE conta=:c").setParameter("c", conta).getSingleResult()).longValue());
    }

    @Test
    void migracaoV10ParaV11PreservaOrdemAnteriorSemCriarHistorico() throws Exception {
        String schema = "fo_traj_legado_" + UUID.randomUUID().toString().replace("-", "");
        UUID ordem = UUID.randomUUID();
        String url = System.getenv("MATCHING_TEST_DB_URL"), usuario = System.getenv("MATCHING_TEST_DB_USER"),
                senha = System.getenv("MATCHING_TEST_DB_PASSWORD");
        try {
            Flyway.configure().dataSource(url, usuario, senha).schemas(schema).target("10").load().migrate();
            try (var c = conexao(); var s = c.prepareStatement("INSERT INTO " + schema + ".ordem "
                    + "(id,conta,ticker,lado,tipo,validade,quantidade,preco_limite,grupo_envio,estado,data_sessao,recebida_em) "
                    + "VALUES(?,'CONTA-002','ALFA3','COMPRA','LIMIT','DIA',10,10,?,'RECEBIDA','2026-10-25',now())")) {
                s.setObject(1, ordem); s.setObject(2, ordem); s.executeUpdate();
            }
            Flyway.configure().dataSource(url, usuario, senha).schemas(schema).target("11").load().migrate();
            try (var c = conexao(); var s = c.createStatement()) {
                try (var r = s.executeQuery("SELECT count(*) FROM " + schema + ".ordem")) { assertTrue(r.next()); assertEquals(1, r.getInt(1)); }
                try (var r = s.executeQuery("SELECT count(*) FROM " + schema + ".trajetoria_marco")) { assertTrue(r.next()); assertEquals(0, r.getInt(1)); }
                try (var r = s.executeQuery("SELECT count(*) FROM " + schema + ".trajetoria_revisao WHERE revisao=0")) { assertTrue(r.next()); assertEquals(4, r.getInt(1)); }
            }
        } finally {
            try (var c = conexao(); var s = c.createStatement()) { s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
        }
    }

    @Test
    void confirmacaoBoDeduplicaAtualizaQuatroContasESemInventarMatching() {
        UUID compra = criar(), venda = criar("CONTA-003", "VENDA");
        var evento = contabilizacao(compra, venda, "CONTA-003", 4);
        var contas = List.of("CONTA-001", "CONTA-002", "CONTA-003", "CONTA-004");
        var antes = contas.stream().map(this::revisao).toList();
        assertTrue(trajetoria.registrarContabilizacao(evento));
        for (int i = 0; i < contas.size(); i++) assertEquals(antes.get(i) + 1, revisao(contas.get(i)));
        assertFalse(trajetoria.registrarContabilizacao(evento));
        for (int i = 0; i < contas.size(); i++) assertEquals(antes.get(i) + 1, revisao(contas.get(i)));
        for (var alvo : Map.of(compra, "CONTA-002", venda, "CONTA-003").entrySet()) {
            var pagina = trajetoria.consultar(alvo.getValue(), alvo.getKey(), 0, 20).orElseThrow();
            assertEquals(1, pagina.eventos().size());
            assertEquals("NEGOCIO_CONTABILIZADO", pagina.eventos().getFirst().tipo());
            assertEquals(evento.causacao(), pagina.eventos().getFirst().causacao());
            assertEquals("4", pagina.eventos().getFirst().quantidade());
            assertFalse(pagina.historicoCompleto());
            assertEquals("RECEBIDA", QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(
                    "SELECT estado FROM ordem WHERE id=:i").setParameter("i", alvo.getKey()).getSingleResult()));
        }
    }

    @Test
    void confirmacaoBoInvalidaReverteTodasRevisoesEMarcos() {
        UUID compra = criar(), venda = criar("CONTA-003", "VENDA");
        long antes = revisao("CONTA-002");
        for (var evento : List.of(contabilizacao(compra, venda, "CONTA-004", 4),
                contabilizacao(compra, venda, "CONTA-003", 11),
                contabilizacao(compra, compra, "CONTA-002", 4))) {
            assertThrows(MatchingIncoerenteException.class, () -> trajetoria.registrarContabilizacao(evento));
        }
        assertEquals(antes, revisao("CONTA-002"));
        assertTrue(trajetoria.consultar("CONTA-002", compra, 0, 20).orElseThrow().eventos().isEmpty());
        assertTrue(trajetoria.consultar("CONTA-003", venda, 0, 20).orElseThrow().eventos().isEmpty());
    }

    private ResultadoRisco risco(UUID ordem, UUID evento, String assinatura) {
        return new ResultadoRisco(evento, ordem, "CONTA-002", EstadoOrdem.RESERVADA, null, null, null, assinatura);
    }

    private static String token(String conta) {
        return Jwt.issuer("prospero-local").audience("prospero-front-office").subject(conta)
                .groups(Set.of("ordens:leitura")).expiresIn(300)
                .signWithSecret("chave-sintetica-de-teste-com-mais-de-32-bytes");
    }

    private Ordem novaOrdem(UUID id, Instant recebida) {
        return new Ordem(id, "CONTA-002", "ALFA3", LadoOrdem.COMPRA, TipoOrdem.LIMIT, Validade.DIA,
                10, new BigDecimal("10.00"), null, null, null, id, EstadoOrdem.RECEBIDA,
                LocalDate.of(2026, 10, 25), recebida, null);
    }

    @Test
    void recebimentoConfirmaComOutboxERepeticaoNaoCriaMarco() {
        UUID id = UUID.randomUUID(), comando = UUID.randomUUID();
        Instant recebida = Instant.parse("2026-10-25T10:00:00Z");
        Ordem ordem = novaOrdem(id, recebida);
        var mensagem = new MensagemSaida(comando, "risco.comandos.v1", "ALFA3", "RESERVAR", "{}", null, recebida);
        ordens.registrar("CONTA-002", id.toString(), "f".repeat(64), recebida, List.of(ordem), mensagem);
        var primeira = trajetoria.consultar("CONTA-002", id, 0, 20).orElseThrow();
        ordens.registrar("CONTA-002", id.toString(), "f".repeat(64), recebida, List.of(ordem), mensagem);
        var repetida = trajetoria.consultar("CONTA-002", id, 0, 20).orElseThrow();
        assertEquals(primeira, repetida);
        assertTrue(primeira.historicoCompleto());
        assertEquals(1, primeira.eventos().size());
        assertEquals("ORDEM_RECEBIDA", primeira.eventos().getFirst().tipo());
        assertEquals(recebida, primeira.eventos().getFirst().ocorridoEm());
        assertEquals("10", primeira.eventos().getFirst().quantidade());
        assertEquals(1L, QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT count(*) FROM outbox WHERE id=:i").setParameter("i", comando).getSingleResult()).longValue()));
    }

    @Test
    void falhaAposRecebimentoDesfazOrdemOutboxEMarco() {
        UUID id = UUID.randomUUID(), comando = UUID.randomUUID();
        Instant agora = Instant.now();
        var ordem = novaOrdem(id, agora);
        var mensagem = new MensagemSaida(comando, "risco.comandos.v1", "ALFA3", "RESERVAR", "{}", null, agora);
        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            ordens.registrar("CONTA-002", id.toString(), "f".repeat(64), agora, List.of(ordem), mensagem);
            throw new IllegalStateException("falha de transação após recebimento");
        }));
        assertTrue(ordens.buscar("CONTA-002", id).isEmpty());
        assertTrue(trajetoria.consultar("CONTA-002", id, 0, 20).isEmpty());
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT count(*) FROM outbox WHERE id=:i").setParameter("i", comando).getSingleResult()).longValue()));
    }

    @Test
    void consultaRestEntregaSomenteMarcosDaContaSemCache() {
        UUID ordem = criar(), evento = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> trajetoria.registrarRisco(risco(ordem, evento, "a".repeat(64))));
        String rota = "/api/v1/ordens/" + ordem + "/trajetoria";
        given().auth().oauth2(token("CONTA-002")).when().get(rota).then()
                .statusCode(200).header("Cache-Control", "no-store")
                .body("ordem", equalTo(ordem.toString())).body("conta", equalTo("CONTA-002"))
                .body("historicoCompleto", equalTo(false)).body("eventos", hasSize(1))
                .body("eventos[0].evento", equalTo(evento.toString()))
                .body("eventos[0].ocorridoEm", nullValue()).body("proximoCursor", nullValue());
        var alheia = given().auth().oauth2(token("CONTA-003")).when().get(rota).then()
                .statusCode(404).extract().jsonPath();
        var ausente = given().auth().oauth2(token("CONTA-003")).when()
                .get("/api/v1/ordens/" + UUID.randomUUID() + "/trajetoria").then()
                .statusCode(404).extract().jsonPath();
        assertEquals(alheia.getString("codigo"), ausente.getString("codigo"));
        assertEquals(alheia.getString("detail"), ausente.getString("detail"));
    }

    @Test
    void quarentenaDeclaraIndisponibilidadeSemExporPayload() {
        UUID ordem = criar();
        long id = QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery("""
                INSERT INTO evento_rejeitado(topico,particao,deslocamento,motivo,recebido_em)
                VALUES('acompanhamento.eventos.v1',0,987,'INCONSISTENTE',now()) RETURNING id
                """).getSingleResult()).longValue());
        try {
            given().auth().oauth2(token("CONTA-002")).when()
                    .get("/api/v1/ordens/" + ordem + "/trajetoria").then()
                    .statusCode(503).contentType("application/problem+json");
        } finally {
            QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("DELETE FROM evento_rejeitado WHERE id=:i")
                    .setParameter("i", id).executeUpdate());
        }
        assertTrue(trajetoria.consultar("CONTA-002", ordem, 0, 20).isPresent());
    }

    @Test
    void legadoNaoInventaRecebimentoENaoVazaParaOutraConta() {
        UUID ordem = criar();
        var pagina = trajetoria.consultar("CONTA-002", ordem, 0, 20).orElseThrow();
        assertFalse(pagina.historicoCompleto());
        assertNull(pagina.disponivelDesde());
        assertTrue(pagina.eventos().isEmpty());
        assertTrue(trajetoria.consultar("CONTA-003", ordem, 0, 20).isEmpty());
        assertTrue(trajetoria.consultar("CONTA-002", UUID.randomUUID(), 0, 20).isEmpty());
    }

    @Test
    void riscoDeduplicaIdenticoERecusaIdentidadeDivergenteSemAvancarRevisao() {
        UUID ordem = criar(), evento = UUID.randomUUID();
        var risco = risco(ordem, evento, "a".repeat(64));
        QuarkusTransaction.requiringNew().run(() -> trajetoria.registrarRisco(risco));
        var antes = trajetoria.consultar("CONTA-002", ordem, 0, 20).orElseThrow();
        QuarkusTransaction.requiringNew().run(() -> trajetoria.registrarRisco(risco));
        assertThrows(MatchingIncoerenteException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                trajetoria.registrarRisco(risco(ordem, evento, "b".repeat(64)))));
        var depois = trajetoria.consultar("CONTA-002", ordem, 0, 20).orElseThrow();
        assertEquals(antes.revisao(), depois.revisao());
        assertEquals(antes.eventos(), depois.eventos());
        assertEquals(1, depois.eventos().size());
        assertEquals("RISCO_RESERVADO", depois.eventos().getFirst().tipo());
        assertNull(depois.eventos().getFirst().ocorridoEm());
        assertNotNull(depois.eventos().getFirst().observadoEm());
        assertFalse(depois.historicoCompleto());
    }

    @Test
    void identidadeDoEventoNaoPodeSerReutilizadaEmOutraOrdem() {
        UUID primeira = criar(), segunda = criar(), evento = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> trajetoria.registrarRisco(risco(primeira, evento, "a".repeat(64))));
        long revisao = trajetoria.consultar("CONTA-002", primeira, 0, 20).orElseThrow().revisao();
        assertThrows(MatchingIncoerenteException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                trajetoria.registrarRisco(risco(segunda, evento, "b".repeat(64)))));
        var pagina = trajetoria.consultar("CONTA-002", segunda, 0, 20).orElseThrow();
        assertEquals(revisao, pagina.revisao());
        assertTrue(pagina.eventos().isEmpty());
    }

    @Test
    void rollbackReverteMarcoERevisaoJuntoDaMutacaoFinanceira() {
        UUID ordem = criar();
        long antes = trajetoria.consultar("CONTA-002", ordem, 0, 20).orElseThrow().revisao();
        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("UPDATE ordem SET estado='RESERVADA' WHERE id=:i").setParameter("i", ordem).executeUpdate();
            trajetoria.registrarRisco(risco(ordem, UUID.randomUUID(), "c".repeat(64)));
            throw new IllegalStateException("falha após gravar marco");
        }));
        var pagina = trajetoria.consultar("CONTA-002", ordem, 0, 20).orElseThrow();
        assertEquals(antes, pagina.revisao());
        assertTrue(pagina.eventos().isEmpty());
        assertEquals("RECEBIDA", QuarkusTransaction.requiringNew().call(() ->
                em.createNativeQuery("SELECT estado FROM ordem WHERE id=:i").setParameter("i", ordem).getSingleResult()));
    }

    @Test
    void paginacaoCrescenteNaoRepeteNemPerdeMarcos() {
        UUID ordem = criar();
        for (int i = 0; i < 3; i++) {
            QuarkusTransaction.requiringNew().run(() -> trajetoria.registrarRisco(risco(ordem, UUID.randomUUID(), "d".repeat(64))));
        }
        var primeira = trajetoria.consultar("CONTA-002", ordem, 0, 2).orElseThrow();
        assertEquals(2, primeira.eventos().size());
        long ultima = primeira.eventos().getLast().sequencia();
        var segunda = trajetoria.consultar("CONTA-002", ordem, ultima, 2).orElseThrow();
        assertEquals(1, segunda.eventos().size());
        assertTrue(segunda.eventos().getFirst().sequencia() > ultima);
        assertEquals(primeira.revisao(), segunda.revisao());
        assertEquals(primeira.disponivelDesde(), segunda.disponivelDesde());
    }

    @Test
    void revisaoNaoPublicaSegundoCommitEnquantoPrimeiroEstaAberto() throws Exception {
        UUID primeira = criar(), segunda = criar();
        long antes = trajetoria.consultar("CONTA-002", primeira, 0, 20).orElseThrow().revisao();
        var gravouPrimeiro = new CountDownLatch(1);
        var liberarPrimeiro = new CountDownLatch(1);
        var tentouSegundo = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var primeiro = executor.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                trajetoria.registrarRisco(risco(primeira, UUID.randomUUID(), "e".repeat(64)));
                gravouPrimeiro.countDown();
                try {
                    if (!liberarPrimeiro.await(10, TimeUnit.SECONDS)) throw new AssertionError("prazo de liberação");
                } catch (InterruptedException erro) { throw new IllegalStateException(erro); }
            }));
            try {
                assertTrue(gravouPrimeiro.await(10, TimeUnit.SECONDS));
                var segundo = executor.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                    tentouSegundo.countDown();
                    trajetoria.registrarRisco(risco(segunda, UUID.randomUUID(), "f".repeat(64)));
                }));
                assertTrue(tentouSegundo.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> segundo.get(200, TimeUnit.MILLISECONDS));
                var snapshot = trajetoria.consultar("CONTA-002", segunda, 0, 20).orElseThrow();
                assertEquals(antes, snapshot.revisao());
                assertTrue(snapshot.eventos().isEmpty());
                liberarPrimeiro.countDown();
                primeiro.get(10, TimeUnit.SECONDS);
                segundo.get(10, TimeUnit.SECONDS);
                long sequencia1 = trajetoria.consultar("CONTA-002", primeira, antes, 20).orElseThrow()
                        .eventos().getFirst().sequencia();
                long sequencia2 = trajetoria.consultar("CONTA-002", segunda, antes, 20).orElseThrow()
                        .eventos().getFirst().sequencia();
                assertTrue(sequencia1 > antes);
                assertTrue(sequencia2 > sequencia1);
            } finally {
                liberarPrimeiro.countDown();
            }
        }
    }
}
