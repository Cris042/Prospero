package br.prospero.mo.matching;

import br.prospero.mo.risco.dominio.ComandoRisco;
import br.prospero.mo.risco.dominio.Decisao;
import br.prospero.mo.risco.dominio.Lado;
import br.prospero.mo.risco.dominio.Perna;
import br.prospero.mo.risco.dominio.TipoOrdem;
import br.prospero.mo.risco.dominio.Validade;
import br.prospero.mo.risco.helper.CalculoReserva;
import br.prospero.mo.risco.jpa.RepositorioRiscoJpa;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RF05c.3b2 (QA): admissão real do par pelo JPA para venda (custódia, sem preço máximo) e compra com Stop Limit;
 * repetir a admissão falha sem duplicar pernas. Opt-in -Dtest=OcoAdmissaoQaPostgresIT, banco do MatchingPostgresIT.
 */
@QuarkusTest
@TestProfile(MatchingPostgresIT.Banco.class)
class OcoAdmissaoQaPostgresIT {
    @AfterAll
    static void removerSchemaProprio() throws Exception {
        MatchingPostgresIT.removerSchemaProprio();
    }

    @Inject EntityManager em;
    @Inject RepositorioRiscoJpa risco;

    private void limpar() {
        for (String tabela : new String[]{"matching_evento", "matching_negocio", "reserva_perna", "reserva", "decisao_risco"}) {
            em.createNativeQuery("DELETE FROM " + tabela).executeUpdate();
        }
        em.createNativeQuery("UPDATE conta_risco SET total=1000,disponivel=1000,reservado=0").executeUpdate();
        em.createNativeQuery("UPDATE custodia_risco SET total=100,disponivel=100,reservado=0").executeUpdate();
    }

    private ComandoRisco par(String conta, Lado lado, long q, String alvo, TipoOrdem tipoStop, String limite, String gatilho) {
        var tp = new Perna(UUID.randomUUID(), TipoOrdem.LIMIT, q, new BigDecimal(alvo), null, Validade.DIA);
        var sl = new Perna(UUID.randomUUID(), tipoStop, q, limite == null ? null : new BigDecimal(limite),
                new BigDecimal(gatilho), Validade.DIA);
        return new ComandoRisco(UUID.randomUUID(), UUID.randomUUID(), conta, "ALFA3", lado, new BigDecimal("10.00"),
                List.of(tp, sl), null, LocalDate.of(2026, 10, 18));
    }

    private Decisao admitir(ComandoRisco par) {
        var decisao = Decisao.reservada(par, CalculoReserva.exigencia(par, risco.parametros("ALFA3").orElseThrow()), 1,
                Instant.parse("2026-10-18T12:00:00Z"));
        QuarkusTransaction.requiringNew().run(() -> {
            limpar();
            risco.bloquearConta(par.conta());
            risco.registrar(decisao);
            risco.registrarAdmissao(par, decisao);
        });
        return decisao;
    }

    private Object[] grupo(UUID intencao) {
        return QuarkusTransaction.requiringNew().call(() -> (Object[]) em.createNativeQuery(
                "SELECT oco,ordem,recurso,preco_maximo,valor_restante,quantidade_inicial,quantidade_restante,validade "
                        + "FROM reserva WHERE intencao=:i").setParameter("i", intencao).getSingleResult());
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> pernas(UUID intencao) {
        return QuarkusTransaction.requiringNew().call(() -> (List<Object[]>) em.createNativeQuery(
                "SELECT ordem,papel,tipo,preco_limite,preco_gatilho,quantidade FROM reserva_perna WHERE intencao=:i "
                        + "ORDER BY papel DESC").setParameter("i", intencao).getResultList());
    }

    @Test
    void vendaGravaGrupoDeCustodiaSemPrecoMaximoEPernasNaOrdem() {
        var par = par("CONTA-003", Lado.VENDA, 3, "10.20", TipoOrdem.STOP, null, "9.80");
        admitir(par);
        Object[] g = grupo(par.intencao());
        assertEquals(Boolean.TRUE, g[0]);
        assertNull(g[1], "o grupo continua sem ordem própria");
        assertEquals("CUSTODIA", g[2]);
        assertNull(g[3]);
        assertNull(g[4]);
        assertEquals(3L, ((Number) g[5]).longValue());
        assertEquals(3L, ((Number) g[6]).longValue());
        assertEquals("DIA", g[7]);
        var p = pernas(par.intencao());
        assertEquals(2, p.size());
        assertEquals(par.pernas().get(0).id(), p.get(0)[0]);
        assertEquals("TAKE_PROFIT", p.get(0)[1]);
        assertEquals("LIMIT", p.get(0)[2]);
        assertEquals(0, new BigDecimal("10.20").compareTo((BigDecimal) p.get(0)[3]));
        assertNull(p.get(0)[4]);
        assertEquals(par.pernas().get(1).id(), p.get(1)[0]);
        assertEquals("STOP_LOSS", p.get(1)[1]);
        assertEquals("STOP", p.get(1)[2]);
        assertNull(p.get(1)[3]);
        assertEquals(0, new BigDecimal("9.80").compareTo((BigDecimal) p.get(1)[4]));
        assertEquals(3L, ((Number) p.get(1)[5]).longValue());
    }

    @Test
    void compraComStopLimitGravaPrecoMaximoDoLimiteEReadmissaoFalhaSemDuplicar() {
        var par = par("CONTA-002", Lado.COMPRA, 2, "9.80", TipoOrdem.STOP_LIMIT, "10.30", "10.20");
        var decisao = admitir(par);
        Object[] g = grupo(par.intencao());
        assertEquals("CAIXA", g[2]);
        assertEquals(0, new BigDecimal("10.30").compareTo((BigDecimal) g[3]));
        assertEquals(0, new BigDecimal("20.60").compareTo((BigDecimal) g[4]));
        var p = pernas(par.intencao());
        assertEquals("STOP_LIMIT", p.get(1)[2]);
        assertEquals(0, new BigDecimal("10.30").compareTo((BigDecimal) p.get(1)[3]));
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew()
                .run(() -> risco.registrarAdmissao(par, decisao)), "grupo já admitido não é regravado");
        assertEquals(2, pernas(par.intencao()).size(), "falha da readmissão não deixa perna parcial");
    }
}
