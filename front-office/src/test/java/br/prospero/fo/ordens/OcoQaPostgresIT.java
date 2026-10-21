package br.prospero.fo.ordens;

import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.dominio.LadoOrdem;
import br.prospero.fo.ordens.dominio.MatchingIncoerenteException;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import br.prospero.fo.ordens.jpa.RepositorioMatchingJpa;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** QA independente da RF05c.3b1: regra de versão por OCO/stop, OCO legado e migração V8. Opt-in -Dtest=OcoQaPostgresIT. */
@QuarkusTest
@TestProfile(MatchingPostgresIT.Banco.class)
class OcoQaPostgresIT {
    @AfterAll
    static void removerSchemaProprio() throws Exception {
        MatchingPostgresIT.removerSchemaProprio();
    }

    @Inject RepositorioMatchingJpa repositorio;
    @Inject EntityManager em;

    private static UUID admissao(UUID intencao) {
        return UUID.nameUUIDFromBytes(("admissao:" + intencao).getBytes(StandardCharsets.UTF_8));
    }

    private record Par(UUID grupo, UUID tp, UUID sl) { }

    private Par par(boolean comAdmissao) {
        Par p = new Par(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        QuarkusTransaction.requiringNew().run(() -> {
            perna(p.tp(), p.grupo(), "TAKE_PROFIT", "LIMIT", new BigDecimal("12.00"), null, comAdmissao);
            perna(p.sl(), p.grupo(), "STOP_LOSS", "STOP", null, new BigDecimal("9.00"), comAdmissao);
        });
        return p;
    }

    private void perna(UUID id, UUID grupo, String papel, String tipo, BigDecimal limite, BigDecimal gatilho, boolean comAdmissao) {
        em.createNativeQuery("""
                INSERT INTO ordem(id,conta,ticker,lado,tipo,validade,quantidade,preco_limite,preco_gatilho,grupo_oco,papel_oco,
                grupo_envio,estado,data_sessao,recebida_em,admissao_matching)
                VALUES(:i,'CONTA-003','ALFA3','VENDA',:t,'DIA',10,:l,:g,:o,:p,:o,'RESERVADA','2026-10-18',now(),:a)
                """).unwrap(org.hibernate.query.NativeQuery.class).setParameter("i", id).setParameter("t", tipo)
                .setParameter("l", limite, BigDecimal.class).setParameter("g", gatilho, BigDecimal.class)
                .setParameter("o", grupo).setParameter("p", papel)
                .setParameter("a", comAdmissao ? admissao(grupo) : null, UUID.class).executeUpdate();
    }

    private UUID simples(String tipo, String lado) {
        UUID id = UUID.randomUUID();
        boolean stop = tipo.equals("STOP");
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
                INSERT INTO ordem(id,conta,ticker,lado,tipo,validade,quantidade,preco_limite,preco_gatilho,grupo_envio,
                estado,data_sessao,recebida_em,admissao_matching)
                VALUES(:i,'CONTA-002','ALFA3',:l,:t,'DIA',10,:p,:g,:i,'RESERVADA','2026-10-18',now(),:a)
                """).unwrap(org.hibernate.query.NativeQuery.class).setParameter("i", id).setParameter("l", lado)
                .setParameter("t", tipo).setParameter("p", stop ? null : new BigDecimal("12.00"), BigDecimal.class)
                .setParameter("g", stop ? new BigDecimal("13.00") : null, BigDecimal.class)
                .setParameter("a", admissao(id)).executeUpdate());
        return id;
    }

    private EventoMatching evento(int versao, String tipo, UUID ordem, UUID intencao, String conta, UUID raiz, String campos) {
        String causacao = versao == 1 ? "" : "\"causacao\":\"" + admissao(raiz) + "\",";
        String s = """
                {"id":"%s","tipo":"%s","versao":%d,"origem":"matching-engine","correlacao":"%s",%s
                "ocorridoEm":"2026-10-18T12:00:00Z","ticker":"ALFA3","dataSessao":"2026-10-18","traceparent":null,
                "dados":{"ordem":"%s","intencao":"%s","conta":"%s",%s}}
                """.formatted(UUID.randomUUID(), tipo, versao, admissao(intencao), causacao, ordem, intencao, conta, campos);
        return EnvelopeMatching.evento(s.getBytes(StandardCharsets.UTF_8), "ALFA3");
    }

    private String estado(UUID ordem) {
        return QuarkusTransaction.requiringNew().call(() -> (String) em.createNativeQuery(
                "SELECT estado FROM ordem WHERE id=:i").setParameter("i", ordem).getSingleResult());
    }

    private EventoMatching negocio(int versao, UUID compra, Par p) {
        var detalhe = new EventoMatching.Negocio("ALFA3-2026-10-18-777",
                new BigDecimal("12.00"), 2, new EventoMatching.Alvo(compra, compra, "CONTA-002"),
                new EventoMatching.Alvo(p.tp(), p.grupo(), "CONTA-003"), LadoOrdem.COMPRA);
        return new EventoMatching(UUID.randomUUID(), admissao(compra), "ALFA3", LocalDate.of(2026, 10, 18),
                "hash-" + UUID.randomUUID(), detalhe, versao == 1 ? null : admissao(compra), versao);
    }

    @Test
    void versaoTresExatamenteComOcoEDoisSemOcoComStop() throws Exception {
        Par p = par(true);
        assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(
                evento(2, "STOP_PENDENTE", p.sl(), p.grupo(), "CONTA-003", p.grupo(), "\"precoGatilho\":\"9.00\"")), "OCO em v2");
        assertTrue(repositorio.aplicar(
                evento(3, "STOP_PENDENTE", p.sl(), p.grupo(), "CONTA-003", p.grupo(), "\"precoGatilho\":\"9.00\"")));
        assertEquals("AGUARDANDO_GATILHO", estado(p.sl()));

        UUID stop = simples("STOP", "COMPRA");
        assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(
                evento(3, "ORDEM_ACEITA", stop, stop, "CONTA-002", stop, "\"sequencia\":1")), "stop simples em v3");
        assertTrue(repositorio.aplicar(evento(2, "ORDEM_ACEITA", stop, stop, "CONTA-002", stop, "\"sequencia\":1")));

        UUID compra = simples("LIMIT", "COMPRA");
        assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(negocio(1, compra, p)), "negócio com TP em v1");
        assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(negocio(2, compra, p)), "negócio com TP em v2");
        repositorio.aplicar(evento(3, "ORDEM_ACEITA", p.tp(), p.grupo(), "CONTA-003", p.grupo(), "\"sequencia\":1"));
        assertTrue(repositorio.aplicar(negocio(3, compra, p)), "negócio simples x TP em v3");
        assertEquals("PARCIALMENTE_EXECUTADA", estado(p.tp()));
        assertEquals("PARCIALMENTE_EXECUTADA", estado(compra));
    }

    @Test
    void terminalDeOrdemSimplesCausadoPorRaizOcoExigeV3() throws Exception {
        Par p = par(true);
        UUID compra = simples("LIMIT", "COMPRA");
        String s = """
                {"id":"%s","tipo":"ORDEM_ENCERRADA","versao":1,"origem":"matching-engine","correlacao":"%s",
                "ocorridoEm":"2026-10-18T12:00:00Z","ticker":"ALFA3","dataSessao":"2026-10-18","traceparent":null,
                "dados":{"ordem":"%s","intencao":"%s","conta":"CONTA-002","motivo":"SEM_LIQUIDEZ","executada":0,"cancelada":10}}
                """.formatted(UUID.randomUUID(), admissao(p.grupo()), compra, compra);
        EventoMatching v1 = EnvelopeMatching.evento(s.getBytes(StandardCharsets.UTF_8), "ALFA3");
        assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(v1), "raiz OCO em v1");
        assertEquals("RESERVADA", estado(compra));
    }

    @Test
    void ocoLegadoSemAdmissaoContinuaRecusado() {
        Par p = par(false);
        for (UUID ordem : new UUID[]{p.tp(), p.sl()}) {
            assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(
                    evento(3, "ORDEM_ACEITA", ordem, p.grupo(), "CONTA-003", p.grupo(), "\"sequencia\":1")));
        }
        assertEquals("RESERVADA", estado(p.tp()));
        assertEquals("RESERVADA", estado(p.sl()));
    }

    @Test
    void migracaoV8PreservaLegadoEUnicidadePorPapel() throws Exception {
        String proprio = "upgrade_oco_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("MATCHING_TEST_DB_URL"), usuario = System.getenv("MATCHING_TEST_DB_USER"),
                senha = System.getenv("MATCHING_TEST_DB_PASSWORD");
        UUID grupo = UUID.randomUUID(), tp = UUID.randomUUID(), sl = UUID.randomUUID(), simples = UUID.randomUUID();
        var configuracao = org.flywaydb.core.Flyway.configure().dataSource(url, usuario, senha)
                .schemas(proprio).defaultSchema(proprio).locations("classpath:db/migration");
        String insert = "INSERT INTO ordem(id,conta,ticker,lado,tipo,validade,quantidade,preco_limite,preco_gatilho,grupo_oco,papel_oco,"
                + "grupo_envio,estado,data_sessao,recebida_em,admissao_matching) VALUES";
        try {
            configuracao.target("7").load().migrate();
            try (var c = DriverManager.getConnection(url, usuario, senha); var s = c.createStatement()) {
                c.setSchema(proprio);
                s.execute(insert + "('" + tp + "','CONTA-003','ALFA3','VENDA','LIMIT','DIA',10,12,NULL,'" + grupo + "','TAKE_PROFIT','"
                        + grupo + "','RESERVADA','2026-10-18',now(),NULL)");
                s.execute(insert + "('" + sl + "','CONTA-003','ALFA3','VENDA','STOP','DIA',10,NULL,9,'" + grupo + "','STOP_LOSS','"
                        + grupo + "','RESERVADA','2026-10-18',now(),NULL)");
                s.execute(insert + "('" + simples + "','CONTA-002','ALFA3','COMPRA','LIMIT','DIA',10,12,NULL,NULL,NULL,'"
                        + simples + "','RESERVADA','2026-10-18',now(),'" + admissao(simples) + "')");
            }
            configuracao.target("latest").load().migrate();
            try (var c = DriverManager.getConnection(url, usuario, senha); var s = c.createStatement()) {
                c.setSchema(proprio);
                try (var r = s.executeQuery("SELECT count(*) FROM ordem WHERE quantidade_reduzida=0 AND (admissao_matching IS NULL) = (grupo_oco IS NOT NULL)")) {
                    assertTrue(r.next()); assertEquals(3, r.getInt(1), "legado preservado, sem backfill");
                }
                UUID novo = UUID.randomUUID();
                s.execute(insert + "('" + UUID.randomUUID() + "','CONTA-003','ALFA3','VENDA','LIMIT','DIA',10,12,NULL,'" + novo
                        + "','TAKE_PROFIT','" + novo + "','RESERVADA','2026-10-18',now(),'" + admissao(novo) + "')");
                s.execute(insert + "('" + UUID.randomUUID() + "','CONTA-003','ALFA3','VENDA','STOP','DIA',10,NULL,9,'" + novo
                        + "','STOP_LOSS','" + novo + "','RESERVADA','2026-10-18',now(),'" + admissao(novo) + "')");
                assertThrows(java.sql.SQLException.class, () -> s.execute(insert + "('" + UUID.randomUUID()
                        + "','CONTA-002','ALFA3','COMPRA','LIMIT','DIA',10,12,NULL,NULL,NULL,'" + simples
                        + "','RESERVADA','2026-10-18',now(),'" + admissao(simples) + "')"), "simples duplicada");
                assertThrows(java.sql.SQLException.class, () -> s.execute(
                        "UPDATE ordem SET quantidade_reduzida=1 WHERE id='" + tp + "'"), "TP não reduz");
                assertThrows(java.sql.SQLException.class, () -> s.execute(
                        "UPDATE ordem SET quantidade_reduzida=6,quantidade_executada=5 WHERE id='" + sl + "'"), "conservação");
                assertEquals(1, s.executeUpdate("UPDATE ordem SET quantidade_reduzida=4,quantidade_cancelada=6 WHERE id='" + sl + "'"));
            }
        } finally {
            try (var c = DriverManager.getConnection(url, usuario, senha); var s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + proprio + " CASCADE");
            }
        }
    }
}
