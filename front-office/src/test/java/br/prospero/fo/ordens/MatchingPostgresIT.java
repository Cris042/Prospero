package br.prospero.fo.ordens;

import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.dominio.LadoOrdem;
import br.prospero.fo.ordens.dominio.MatchingIncoerenteException;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import br.prospero.fo.ordens.jpa.RepositorioMatchingJpa;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: -Dtest=MatchingPostgresIT com MATCHING_TEST_DB_*; somente schema próprio descartável. */
@QuarkusTest
@TestProfile(MatchingPostgresIT.Banco.class)
class MatchingPostgresIT {
    private static final String SCHEMA = schemaDaExecucao();

    private static String schemaDaExecucao() {
        synchronized (System.getProperties()) {
            return System.getProperties().computeIfAbsent("prospero.it.fo.schema",
                    chave -> "fo_matching_it_" + UUID.randomUUID().toString().replace("-", "")).toString();
        }
    }

    public static class Banco implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            try (var c = conexao(); var s = c.createStatement()) { s.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA); }
            catch (Exception erro) { throw new IllegalStateException("Banco descartável obrigatório", erro); }
            return Map.of("quarkus.datasource.active", "true", "quarkus.hibernate-orm.active", "true", "quarkus.datasource.jdbc.url",
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

    @Inject RepositorioMatchingJpa repositorio;
    @Inject EntityManager em;

    private UUID criar(String conta, String lado) {
        UUID id = UUID.randomUUID();
        UUID admissao = UUID.nameUUIDFromBytes(("admissao:" + id).getBytes(StandardCharsets.UTF_8));
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
                INSERT INTO ordem(id,conta,ticker,lado,tipo,validade,quantidade,preco_limite,grupo_envio,
                estado,data_sessao,recebida_em,admissao_matching)
                VALUES(:i,:c,'ALFA3',:l,'LIMIT','IOC',10,10,:i,'RECEBIDA','2026-10-18',now(),:a)
                """).setParameter("i", id).setParameter("c", conta).setParameter("l", lado)
                .setParameter("a", admissao).executeUpdate());
        return id;
    }

    private EventoMatching negocio(UUID compra, UUID venda, String contaVenda, String numero, UUID evento) {
        var detalhe = new EventoMatching.Negocio("ALFA3-2026-10-18-" + numero, new BigDecimal("10.00"), 4,
                new EventoMatching.Alvo(compra, compra, "CONTA-002"),
                new EventoMatching.Alvo(venda, venda, contaVenda), LadoOrdem.COMPRA);
        return new EventoMatching(evento, UUID.nameUUIDFromBytes(("admissao:" + compra).getBytes(StandardCharsets.UTF_8)),
                "ALFA3", LocalDate.of(2026, 10, 18), EnvelopeMatching.assinatura((evento + numero + contaVenda).getBytes(StandardCharsets.UTF_8)), detalhe);
    }

    private Object[] estado(UUID ordem) {
        return QuarkusTransaction.requiringNew().call(() -> (Object[]) em.createNativeQuery(
                "SELECT estado,quantidade_executada FROM ordem WHERE id=:i").setParameter("i", ordem).getSingleResult());
    }

    @Test
    void negocioAntesDoRiscoAtualizaAmbosAtomicamenteEDeduplicaComNovoEnvelope() {
        UUID compra = criar("CONTA-002", "COMPRA"), venda = criar("CONTA-003", "VENDA");
        EventoMatching evento = negocio(compra, venda, "CONTA-003", "1", UUID.randomUUID());
        assertTrue(repositorio.aplicar(evento));
        assertFalse(repositorio.aplicar(evento));
        assertFalse(repositorio.aplicar(negocio(compra, venda, "CONTA-003", "1", UUID.randomUUID())));
        for (UUID ordem : new UUID[]{compra, venda}) {
            Object[] e = estado(ordem);
            assertEquals("PARCIALMENTE_EXECUTADA", e[0]);
            assertEquals(4, ((Number) e[1]).longValue());
        }
    }

    @Test
    void segundoLadoInvalidoReverteProgressoEDedupDosDoisLados() {
        UUID compra = criar("CONTA-002", "COMPRA"), venda = criar("CONTA-003", "VENDA");
        assertThrows(MatchingIncoerenteException.class,
                () -> repositorio.aplicar(negocio(compra, venda, "CONTA-001", "2", UUID.randomUUID())));
        assertEquals("RECEBIDA", estado(compra)[0]);
        assertEquals("RECEBIDA", estado(venda)[0]);
        assertTrue(repositorio.aplicar(negocio(compra, venda, "CONTA-003", "2", UUID.randomUUID())));
    }

    @Test
    void correlacaoDivergenteBloqueiaAntesDeAlterarOrdemOuDedup() {
        UUID compra = criar("CONTA-002", "COMPRA"), venda = criar("CONTA-003", "VENDA");
        EventoMatching correto = negocio(compra, venda, "CONTA-003", "3", UUID.randomUUID());
        EventoMatching adulterado = new EventoMatching(correto.id(), UUID.randomUUID(), correto.ticker(),
                correto.sessao(), correto.assinatura(), correto.detalhe());
        assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(adulterado));
        assertEquals("RECEBIDA", estado(compra)[0]);
        assertEquals("RECEBIDA", estado(venda)[0]);
        assertTrue(repositorio.aplicar(correto), "falha não consome identidade do evento");
    }
}
