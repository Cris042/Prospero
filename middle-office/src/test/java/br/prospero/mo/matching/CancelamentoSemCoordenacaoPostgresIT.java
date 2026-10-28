package br.prospero.mo.matching;

import br.prospero.mo.comum.ContratosV5;
import br.prospero.mo.matching.actor.AplicarMatchingActor;
import br.prospero.mo.matching.helper.EnvelopeMatching;
import br.prospero.mo.mensageria.dominio.ParticaoBloqueadaException;
import br.prospero.mo.risco.helper.EnvelopeAdmissao;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RF10c2a: v5 sem solicitação persistida bloqueia a partição sem tocar reserva, caixa, custódia nem deduplicação. */
@QuarkusTest
@TestProfile(MatchingPostgresIT.Banco.class)
class CancelamentoSemCoordenacaoPostgresIT {
    private static final UUID ORDEM = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID INTENCAO = UUID.fromString("b0000000-0000-4000-8000-000000000001");

    @AfterAll
    static void removerSchemaProprio() throws Exception {
        MatchingPostgresIT.removerSchemaProprio();
    }

    @Inject AplicarMatchingActor aplicar;
    @Inject EntityManager em;

    /** Venda de 10 com 4 executadas (restante 6) do alvo das fixtures, com a custódia reservada. */
    private void prepararReservaDoAlvo() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (String tabela : new String[]{"liquidacao_expectativa", "liquidacao_aplicada", "liquidacao_aguardando",
                    "liquidacao_recusada", "matching_evento", "matching_negocio", "reserva_perna", "reserva", "decisao_risco"}) {
                em.createNativeQuery("DELETE FROM " + tabela).executeUpdate();
            }
            em.createNativeQuery("""
                    INSERT INTO decisao_risco(intencao,evento,conta,ticker,lado,resultado,valor_reservado,
                    quantidade_reservada,versao_parametros,decidida_em)
                    VALUES(:i,:e,'CONTA-001','ALFA3','VENDA','RESERVADA',NULL,10,1,now())
                    """).unwrap(org.hibernate.query.NativeQuery.class).setParameter("i", INTENCAO)
                    .setParameter("e", UUID.randomUUID()).executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO reserva(intencao,conta,ticker,recurso,quantidade,estado,criada_em,ordem,admissao,tipo,validade,
                    data_sessao,quantidade_inicial,quantidade_restante,preco_limite)
                    VALUES(:i,'CONTA-001','ALFA3','CUSTODIA',10,'PARCIAL',now(),:o,:a,'LIMIT','DIA','2026-10-18',10,6,10.00)
                    """).setParameter("i", INTENCAO).setParameter("o", ORDEM).setParameter("a", EnvelopeAdmissao.id(INTENCAO))
                    .executeUpdate();
            em.createNativeQuery("UPDATE custodia_risco SET total=96,disponivel=90,reservado=6 WHERE conta='CONTA-001' AND ticker='ALFA3'")
                    .executeUpdate();
        });
    }

    private List<String> estado() {
        return QuarkusTransaction.requiringNew().call(() -> List.of(
                em.createNativeQuery("SELECT string_agg(estado||quantidade_restante,',') FROM reserva").getSingleResult().toString(),
                em.createNativeQuery("SELECT string_agg(total||'/'||disponivel||'/'||reservado,',') FROM custodia_risco WHERE conta='CONTA-001' AND ticker='ALFA3'").getSingleResult().toString(),
                em.createNativeQuery("SELECT count(*) FROM matching_evento").getSingleResult().toString(),
                em.createNativeQuery("SELECT count(*) FROM matching_negocio").getSingleResult().toString(),
                em.createNativeQuery("SELECT count(*) FROM reserva_perna").getSingleResult().toString()));
    }

    @Test
    void v5SemCoordenacaoBloqueiaSemTocarReservaNemDeduplicacao() throws Exception {
        prepararReservaDoAlvo();
        List<String> antes = estado();
        assertEquals("PARCIAL6", antes.getFirst());
        for (var caso : ContratosV5.casos("matching.eventos.v1", false)) {
            var erro = assertThrows(ParticaoBloqueadaException.class,
                    () -> aplicar.aplicar(EnvelopeMatching.ler(caso.bytes())), caso.nome());
            assertEquals("CANCELAMENTO_SEM_COORDENACAO", erro.getMessage());
            assertEquals(antes, estado(), caso.nome());
        }
        assertEquals(0, new BigDecimal(estado().get(2)).compareTo(BigDecimal.ZERO), "nenhum fato deduplicado");
    }
}
