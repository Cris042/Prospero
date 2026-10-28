package br.prospero.fo.ordens;

import br.prospero.fo.comum.ContratosV5;
import br.prospero.fo.cotacao.jpa.RepositorioCotacaoJpa;
import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.dominio.MatchingIncoerenteException;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import br.prospero.fo.ordens.jpa.RepositorioMatchingJpa;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RF10c2a no FO com PostgreSQL real: terminal v5 e decisão sobre o banco, ordens e cotação. Opt-in como os demais ITs. */
@QuarkusTest
@TestProfile(MatchingPostgresIT.Banco.class)
class CancelamentoManualPostgresIT {
    @AfterAll
    static void removerSchemaProprio() throws Exception {
        MatchingPostgresIT.removerSchemaProprio();
    }

    @Inject RepositorioMatchingJpa repositorio;
    @Inject RepositorioCotacaoJpa cotacao;
    @Inject EntityManager em;

    @BeforeEach
    void limpar() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (String tabela : new String[]{"trajetoria_marco", "trajetoria_identidade", "matching_evento", "cotacao_evento",
                    "cotacao_oferta"}) {
                em.createNativeQuery("DELETE FROM " + tabela).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM ordem WHERE id::text LIKE 'a0000000-0000-4000-8000-%'").executeUpdate();
        });
    }

    private static EventoMatching fixture(String nome) throws Exception {
        return EnvelopeMatching.evento(ContratosV5.ler("matching.eventos.v1/ALFA3/" + nome + ".json"), "ALFA3");
    }

    private static UUID id(String final4) {
        return UUID.fromString("a0000000-0000-4000-8000-00000000" + final4);
    }

    private static UUID intencao(String final4) {
        return UUID.fromString("b0000000-0000-4000-8000-00000000" + final4);
    }

    /** Ordem de venda de 10 com a identidade da fixture; {@code grupo} não nulo a torna perna OCO. */
    private void ordem(String final4, String conta, String tipo, String estado, long executada, UUID grupo) {
        UUID ordem = id(final4), envio = intencao(final4);
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM ordem WHERE id=:i").setParameter("i", ordem).executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO ordem(id,conta,ticker,lado,tipo,validade,quantidade,preco_limite,preco_gatilho,grupo_oco,papel_oco,
                    grupo_envio,estado,data_sessao,recebida_em,admissao_matching,quantidade_executada)
                    VALUES(:i,:c,'ALFA3','VENDA',:t,'DIA',10,:p,:g,:o,:papel,:e,:s,'2026-10-18',now(),:a,:x)
                    """).unwrap(org.hibernate.query.NativeQuery.class).setParameter("i", ordem).setParameter("c", conta)
                    .setParameter("t", tipo)
                    .setParameter("p", tipo.equals("STOP") ? null : new BigDecimal("10.00"), BigDecimal.class)
                    .setParameter("g", tipo.startsWith("STOP") ? new BigDecimal("9.00") : null, BigDecimal.class)
                    .setParameter("o", grupo, UUID.class).setParameter("papel", grupo == null ? null : "TAKE_PROFIT", String.class)
                    .setParameter("e", envio).setParameter("s", estado)
                    .setParameter("a", UUID.nameUUIDFromBytes(("admissao:" + envio).getBytes(StandardCharsets.UTF_8)))
                    .setParameter("x", executada).executeUpdate();
        });
    }

    private String coluna(String coluna, UUID ordem) {
        return QuarkusTransaction.requiringNew().call(() -> String.valueOf(em.createNativeQuery(
                "SELECT " + coluna + " FROM ordem WHERE id=:i").setParameter("i", ordem).getSingleResult()));
    }

    private long marcos(UUID ordem, String tipo) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT count(*) FROM trajetoria_marco WHERE ordem=:i AND tipo=:t")
                .setParameter("i", ordem).setParameter("t", tipo).getSingleResult()).longValue());
    }

    @Test
    void terminalManualCancelaUmaVezEADecisaoAplicadaSegueSemMudarEstado() throws Exception {
        ordem("0001", "CONTA-001", "LIMIT", "PARCIALMENTE_EXECUTADA", 4, null);
        var terminal = fixture("ordem-encerrada-cancelada-manual");
        var decisao = fixture("cancelamento-decidido-aplicado");
        assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(decisao),
                "decisão APLICADO sem o terminal projetado");
        assertTrue(repositorio.aplicar(terminal));
        assertFalse(repositorio.aplicar(terminal), "reentrega idempotente");
        assertEquals("CANCELADA", coluna("estado", id("0001")));
        assertEquals("CANCELADA_MANUAL", coluna("motivo_encerramento", id("0001")));
        assertEquals("6", coluna("quantidade_cancelada", id("0001")));
        assertEquals("4", coluna("quantidade_executada", id("0001")));
        assertTrue(repositorio.aplicar(decisao));
        assertFalse(repositorio.aplicar(decisao));
        assertEquals("CANCELADA", coluna("estado", id("0001")));
        assertEquals(1, marcos(id("0001"), "ORDEM_ENCERRADA"));
        assertEquals(1, marcos(id("0001"), "CANCELAMENTO_DECIDIDO"));
    }

    @Test
    void stopNaoAcionadoEhElegivelEOcoBloqueiaSemAlterarNada() throws Exception {
        ordem("0001", "CONTA-001", "STOP", "AGUARDANDO_GATILHO", 0, null);
        var terminal = fixture("ordem-encerrada-cancelada-manual");
        assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(terminal), "executada 4 numa ordem sem execução");
        assertEquals("AGUARDANDO_GATILHO", coluna("estado", id("0001")));

        ordem("0001", "CONTA-001", "LIMIT", "PARCIALMENTE_EXECUTADA", 4, id("0001"));
        assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(terminal), "perna OCO nunca cancela manualmente");
        assertEquals("PARCIALMENTE_EXECUTADA", coluna("estado", id("0001")));
        assertEquals(0, marcos(id("0001"), "ORDEM_ENCERRADA"));
    }

    @Test
    void recusasSaoRegistradasSemTocarNaOrdem() throws Exception {
        ordem("0002", "CONTA-001", "LIMIT", "ABERTA", 0, null);
        ordem("0003", "CONTA-003", "LIMIT", "ABERTA", 0, null);
        ordem("0004", "CONTA-001", "LIMIT", "ABERTA", 0, id("0004"));
        for (String nome : new String[]{"sem-saldo-vivo", "alvo-divergente", "origem-oco"}) {
            var decisao = fixture("cancelamento-decidido-" + nome);
            assertTrue(repositorio.aplicar(decisao), nome);
            assertFalse(repositorio.aplicar(decisao), nome);
        }
        for (String final4 : new String[]{"0002", "0003", "0004"}) {
            assertEquals("ABERTA", coluna("estado", id(final4)), final4);
            assertEquals("0", coluna("quantidade_cancelada", id(final4)), final4);
        }
    }

    @Test
    void cotacaoRetiraOSaldoDoTerminalEIgnoraADecisaoSemNegocioFicticio() throws Exception {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM cotacao_oferta WHERE ticker='ALFA3'").executeUpdate();
            em.createNativeQuery("INSERT INTO cotacao_oferta(ordem,ticker,sessao,lado,preco,restante) "
                    + "VALUES(:o,'ALFA3','2026-10-18','VENDA',10.00,6)").setParameter("o", id("0001")).executeUpdate();
        });
        var terminal = fixture("ordem-encerrada-cancelada-manual");
        assertTrue(cotacao.aplicar(terminal));
        assertFalse(cotacao.aplicar(terminal));
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT count(*) FROM cotacao_oferta WHERE ordem=:o").setParameter("o", id("0001")).getSingleResult()).longValue()));
        assertFalse(cotacao.aplicar(fixture("cancelamento-decidido-aplicado")), "decisão não muda o livro");
        assertFalse(cotacao.aplicar(fixture("cancelamento-decidido-sem-saldo-vivo")));
    }
}
