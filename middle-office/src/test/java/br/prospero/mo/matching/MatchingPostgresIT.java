package br.prospero.mo.matching;

import br.prospero.mo.matching.actor.AplicarMatchingActor;
import br.prospero.mo.matching.helper.EnvelopeMatching;
import br.prospero.mo.risco.helper.EnvelopeAdmissao;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in -Dtest=MatchingPostgresIT; banco descartável informado pelas variáveis MATCHING_TEST_DB_*. */
@QuarkusTest
@TestProfile(MatchingPostgresIT.Banco.class)
class MatchingPostgresIT {
    private static final String SCHEMA = schemaDaExecucao();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String schemaDaExecucao() {
        // Quarkus usa classloaders distintos para perfil e teste; a propriedade mantém a mesma identidade.
        synchronized (System.getProperties()) {
            return System.getProperties().computeIfAbsent("prospero.it.mo.schema",
                    chave -> "mo_matching_it_" + UUID.randomUUID().toString().replace("-", "")).toString();
        }
    }

    public static class Banco implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            try (var c = conexao(); var s = c.createStatement()) {
                s.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
            } catch (Exception erro) {
                throw new IllegalStateException("Banco descartável obrigatório", erro);
            }
            return Map.of("quarkus.datasource.active", "true", "quarkus.datasource.jdbc.url",
                    System.getenv("MATCHING_TEST_DB_URL") + "?currentSchema=" + SCHEMA,
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
    static void removerSchemaProprio() throws Exception {
        try (var c = conexao(); var s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            try (var consulta = c.prepareStatement("SELECT 1 FROM pg_namespace WHERE nspname = ?")) {
                consulta.setString(1, SCHEMA);
                try (var resultado = consulta.executeQuery()) { assertFalse(resultado.next(), "schema próprio removido"); }
            }
        }
    }

    @Inject AplicarMatchingActor aplicar;
    @Inject EntityManager em;
    @Inject br.prospero.mo.risco.jpa.RepositorioRiscoJpa risco;

    private record Par(UUID compra, UUID venda) { }

    private Par preparar() {
        Par par = new Par(UUID.randomUUID(), UUID.randomUUID());
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM matching_evento").executeUpdate();
            em.createNativeQuery("DELETE FROM matching_negocio").executeUpdate();
            em.createNativeQuery("DELETE FROM reserva").executeUpdate();
            em.createNativeQuery("DELETE FROM decisao_risco").executeUpdate();
            em.createNativeQuery("UPDATE conta_risco SET total=1000,disponivel=1000,reservado=0").executeUpdate();
            em.createNativeQuery("UPDATE custodia_risco SET total=100,disponivel=100,reservado=0").executeUpdate();
            reserva(par.compra(), "CONTA-002", "COMPRA");
            reserva(par.venda(), "CONTA-003", "VENDA");
            em.createNativeQuery("UPDATE conta_risco SET disponivel=900,reservado=100 WHERE conta='CONTA-002'").executeUpdate();
            em.createNativeQuery("UPDATE custodia_risco SET disponivel=90,reservado=10 WHERE conta='CONTA-003' AND ticker='ALFA3'").executeUpdate();
        });
        return par;
    }

    private void reserva(UUID id, String conta, String lado) {
        boolean compra = lado.equals("COMPRA");
        em.createNativeQuery("""
                INSERT INTO decisao_risco(intencao,evento,conta,ticker,lado,resultado,valor_reservado,
                quantidade_reservada,versao_parametros,decidida_em)
                VALUES(:i,:e,:c,'ALFA3',:l,'RESERVADA',:v,:q,1,now())
                """).unwrap(org.hibernate.query.NativeQuery.class).setParameter("i", id).setParameter("e", UUID.randomUUID()).setParameter("c", conta)
                .setParameter("l", lado).setParameter("v", compra ? new BigDecimal("100.00") : null, BigDecimal.class)
                .setParameter("q", compra ? null : 10L, Long.class).executeUpdate();
        em.createNativeQuery("""
                INSERT INTO reserva(intencao,conta,ticker,recurso,valor,quantidade,estado,criada_em,
                ordem,admissao,tipo,validade,data_sessao,quantidade_inicial,quantidade_restante,
                preco_limite,preco_maximo,valor_restante)
                VALUES(:i,:c,'ALFA3',:r,:v,:q,'ATIVA',now(),:i,:a,'LIMIT','IOC','2026-10-18',10,10,:p,:t,:v)
                """).unwrap(org.hibernate.query.NativeQuery.class).setParameter("i", id).setParameter("c", conta).setParameter("r", compra ? "CAIXA" : "CUSTODIA")
                .setParameter("v", compra ? new BigDecimal("100.00") : null, BigDecimal.class)
                .setParameter("q", compra ? null : 10L, Long.class).setParameter("a", EnvelopeAdmissao.id(id))
                .setParameter("p", new BigDecimal(compra ? "10.00" : "9.00"))
                .setParameter("t", compra ? new BigDecimal("10.00") : null, BigDecimal.class).executeUpdate();
    }

    private ObjectNode envelope(UUID intencao, String tipo) {
        ObjectNode e = JSON.createObjectNode();
        e.put("id", UUID.randomUUID().toString()); e.put("tipo", tipo); e.put("versao", 1);
        e.put("origem", "matching-engine"); e.put("correlacao", EnvelopeAdmissao.id(intencao).toString());
        e.put("ocorridoEm", "2026-10-18T12:00:00Z"); e.put("ticker", "ALFA3");
        e.put("dataSessao", "2026-10-18"); e.putNull("traceparent");
        return e;
    }

    private ObjectNode negocio(Par par) {
        ObjectNode e = envelope(par.compra(), "NEGOCIO"), d = e.putObject("dados");
        d.put("negocio", "ALFA3-2026-10-18-1"); d.put("preco", "9.00"); d.put("quantidade", 4);
        d.put("ordemCompra", par.compra().toString()); d.put("intencaoCompra", par.compra().toString());
        d.put("contaCompra", "CONTA-002"); d.put("ordemVenda", par.venda().toString());
        d.put("intencaoVenda", par.venda().toString()); d.put("contaVenda", "CONTA-003"); d.put("agressor", "COMPRA");
        return e;
    }

    private void aplicar(ObjectNode e) throws Exception {
        aplicar.aplicar(EnvelopeMatching.ler(JSON.writeValueAsBytes(e)));
    }

    private BigDecimal valor(String sql) {
        return QuarkusTransaction.requiringNew().call(() -> new BigDecimal(em.createNativeQuery(sql).getSingleResult().toString()));
    }

    private void saldo(String coluna, String esperado) {
        assertEquals(0, new BigDecimal(esperado).compareTo(valor("SELECT " + coluna + " FROM conta_risco WHERE conta='CONTA-002'")));
    }

    @Test
    void parcialPrecoMelhorDedupPorEventoENegocioETerminalLiberamSomenteRestante() throws Exception {
        Par par = preparar(); ObjectNode n = negocio(par);
        aplicar(n); aplicar(n); n.put("id", UUID.randomUUID().toString()); aplicar(n);
        saldo("total", "964"); saldo("reservado", "60"); saldo("disponivel", "904");
        assertEquals(0, new BigDecimal("96").compareTo(valor("SELECT total FROM custodia_risco WHERE conta='CONTA-003' AND ticker='ALFA3'")));
        assertEquals(0, new BigDecimal("1000").compareTo(valor("SELECT total FROM conta_risco WHERE conta='CONTA-003'")), "sem crédito vendedor");
        ObjectNode terminal = envelope(par.compra(), "ORDEM_ENCERRADA"), d = terminal.putObject("dados");
        d.put("ordem", par.compra().toString()); d.put("intencao", par.compra().toString()); d.put("conta", "CONTA-002");
        d.put("motivo", "SALDO_CANCELADO_IOC"); d.put("executada", 4); d.put("cancelada", 6);
        aplicar(terminal); aplicar(terminal);
        saldo("total", "964"); saldo("reservado", "0"); saldo("disponivel", "964");
    }

    @Test
    void segundoLadoInvalidoNaoConsomePrimeiroNemGravaDedup() throws Exception {
        Par par = preparar(); ObjectNode n = negocio(par);
        ((ObjectNode) n.get("dados")).put("contaVenda", "CONTA-001");
        assertThrows(RuntimeException.class, () -> aplicar(n));
        saldo("total", "1000"); saldo("reservado", "100"); saldo("disponivel", "900");
        assertEquals(0, valor("SELECT count(*) FROM matching_evento").intValueExact());
        assertEquals(0, valor("SELECT count(*) FROM matching_negocio").intValueExact());
        aplicar(negocio(par)); saldo("total", "964");
    }

    @Test
    void preparacaoRecusaReservaAtivaEParcialSemApagarRecursosOuFatos() throws Exception {
        Par par = preparar();
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(
                () -> risco.preparar("CONTA-002", new BigDecimal("5000"), Map.of())));
        saldo("total", "1000"); saldo("reservado", "100");
        aplicar(negocio(par));
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(
                () -> risco.preparar("CONTA-002", new BigDecimal("5000"), Map.of())));
        saldo("total", "964"); saldo("reservado", "60");
        assertEquals(2, valor("SELECT count(*) FROM reserva").intValueExact());
        assertEquals(1, valor("SELECT count(*) FROM matching_negocio").intValueExact());
    }

    @Test
    void terminalIncompativelNaoLiberaReservaEJsonComCaudaENegado() throws Exception {
        Par par = preparar();
        for (String motivo : new String[]{"EXECUTADA", "FOK_SEM_LIQUIDEZ"}) {
            ObjectNode e = envelope(par.compra(), "ORDEM_ENCERRADA"), d = e.putObject("dados");
            d.put("ordem", par.compra().toString()); d.put("intencao", par.compra().toString()); d.put("conta", "CONTA-002");
            d.put("motivo", motivo); d.put("executada", 0); d.put("cancelada", 10);
            assertThrows(RuntimeException.class, () -> aplicar(e));
            saldo("total", "1000"); saldo("reservado", "100");
        }
        byte[] comCauda = (JSON.writeValueAsString(negocio(par)) + " {}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(br.prospero.mo.mensageria.dominio.MensagemInvalidaException.class,
                () -> EnvelopeMatching.ler(comCauda));
        ObjectNode n = negocio(par); ((ObjectNode) n.get("dados")).put("quantidade", 10); aplicar(n);
        ObjectNode e = envelope(par.compra(), "ORDEM_ENCERRADA"), d = e.putObject("dados");
        d.put("ordem", par.compra().toString()); d.put("intencao", par.compra().toString()); d.put("conta", "CONTA-002");
        d.put("motivo", "SEM_LIQUIDEZ"); d.put("executada", 10); d.put("cancelada", 0);
        assertThrows(RuntimeException.class, () -> aplicar(e), "saldo zero exige motivo EXECUTADA");
        assertEquals("PARCIAL", QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(
                "SELECT estado FROM reserva WHERE intencao=:i").setParameter("i", par.compra()).getSingleResult()));
    }
}
