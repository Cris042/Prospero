package br.prospero.mo.matching;

import br.prospero.mo.matching.actor.AplicarMatchingActor;
import br.prospero.mo.matching.helper.EnvelopeMatching;
import br.prospero.mo.risco.helper.EnvelopeAdmissao;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RF05c.4a: expiração DIA (v4) e rejeição de admissão tardia liberam a reserva uma vez. Opt-in como os demais ITs. */
@QuarkusTest
@TestProfile(MatchingPostgresIT.Banco.class)
class EncerramentoPostgresIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID ENCERRAMENTO = UUID.nameUUIDFromBytes("encerramento:ALFA3:2026-10-18".getBytes(StandardCharsets.UTF_8));

    @AfterAll
    static void removerSchemaProprio() throws Exception {
        MatchingPostgresIT.removerSchemaProprio();
    }

    @Inject AplicarMatchingActor aplicar;
    @Inject EntityManager em;

    private void limpar() {
        for (String tabela : new String[]{"liquidacao_expectativa", "liquidacao_aplicada", "liquidacao_aguardando", "liquidacao_recusada",
                "matching_evento", "matching_negocio", "reserva_perna", "reserva", "decisao_risco"}) {
            em.createNativeQuery("DELETE FROM " + tabela).executeUpdate();
        }
        em.createNativeQuery("UPDATE conta_risco SET total=1000,disponivel=1000,reservado=0").executeUpdate();
        em.createNativeQuery("UPDATE custodia_risco SET total=100,disponivel=100,reservado=0").executeUpdate();
    }

    private void decisao(UUID id, String conta, String lado, BigDecimal valor, Long quantidade) {
        em.createNativeQuery("""
                INSERT INTO decisao_risco(intencao,evento,conta,ticker,lado,resultado,valor_reservado,
                quantidade_reservada,versao_parametros,decidida_em)
                VALUES(:i,:e,:c,'ALFA3',:l,'RESERVADA',:v,:q,1,now())
                """).unwrap(org.hibernate.query.NativeQuery.class).setParameter("i", id).setParameter("e", UUID.randomUUID())
                .setParameter("c", conta).setParameter("l", lado).setParameter("v", valor, BigDecimal.class)
                .setParameter("q", quantidade, Long.class).executeUpdate();
    }

    /** Compra Limit DIA de 10 a 10,00 com 4 já executadas (restante 6, valor 60). */
    private UUID compraParcial() {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            limpar();
            decisao(id, "CONTA-002", "COMPRA", new BigDecimal("100.00"), null);
            em.createNativeQuery("""
                    INSERT INTO reserva(intencao,conta,ticker,recurso,valor,estado,criada_em,ordem,admissao,tipo,validade,
                    data_sessao,quantidade_inicial,quantidade_restante,preco_limite,preco_maximo,valor_restante)
                    VALUES(:i,'CONTA-002','ALFA3','CAIXA',100.00,'PARCIAL',now(),:i,:a,'LIMIT','DIA','2026-10-18',10,6,
                    10.00,10.00,60.00)
                    """).setParameter("i", id).setParameter("a", EnvelopeAdmissao.id(id)).executeUpdate();
            em.createNativeQuery("UPDATE conta_risco SET total=960,disponivel=900,reservado=60 WHERE conta='CONTA-002'")
                    .executeUpdate();
        });
        return id;
    }

    /** Par OCO de venda de 10 com SL pendente reduzido em 4 (TP executou 4). */
    private UUID[] ocoReduzido() {
        UUID g = UUID.randomUUID(), tp = UUID.randomUUID(), sl = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            limpar();
            decisao(g, "CONTA-003", "VENDA", null, 10L);
            em.createNativeQuery("""
                    INSERT INTO reserva(intencao,conta,ticker,recurso,quantidade,estado,criada_em,admissao,validade,
                    data_sessao,quantidade_inicial,quantidade_restante,oco)
                    VALUES(:i,'CONTA-003','ALFA3','CUSTODIA',10,'PARCIAL',now(),:a,'DIA','2026-10-18',10,6,TRUE)
                    """).setParameter("i", g).setParameter("a", EnvelopeAdmissao.id(g)).executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO reserva_perna(ordem,intencao,papel,tipo,preco_limite,preco_gatilho,quantidade,executada,estado)
                    VALUES(:o,:i,'TAKE_PROFIT','LIMIT',12.00,NULL,10,4,'PARCIAL')
                    """).setParameter("o", tp).setParameter("i", g).executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO reserva_perna(ordem,intencao,papel,tipo,preco_limite,preco_gatilho,quantidade,reduzida)
                    VALUES(:o,:i,'STOP_LOSS','STOP',NULL,9.00,10,4)
                    """).setParameter("o", sl).setParameter("i", g).executeUpdate();
            em.createNativeQuery("UPDATE custodia_risco SET total=96,disponivel=90,reservado=6 WHERE conta='CONTA-003' AND ticker='ALFA3'")
                    .executeUpdate();
        });
        return new UUID[]{g, tp, sl};
    }

    private ObjectNode expirada(UUID intencao, UUID ordem, String conta, long executada, long cancelada, UUID causacao) {
        ObjectNode e = JSON.createObjectNode();
        e.put("id", UUID.randomUUID().toString()); e.put("tipo", "ORDEM_ENCERRADA"); e.put("versao", 4);
        e.put("origem", "matching-engine"); e.put("correlacao", EnvelopeAdmissao.id(intencao).toString());
        e.put("causacao", causacao.toString());
        e.put("ocorridoEm", "2026-10-18T21:00:00Z"); e.put("ticker", "ALFA3");
        e.put("dataSessao", "2026-10-18"); e.putNull("traceparent");
        ObjectNode d = e.putObject("dados");
        d.put("ordem", ordem.toString()); d.put("intencao", intencao.toString()); d.put("conta", conta);
        d.put("motivo", "EXPIRADA_DIA"); d.put("executada", executada); d.put("cancelada", cancelada);
        return e;
    }

    private void aplicar(ObjectNode e) throws Exception {
        aplicar.aplicar(EnvelopeMatching.ler(JSON.writeValueAsBytes(e)));
    }

    private BigDecimal decimal(String sql) {
        return QuarkusTransaction.requiringNew().call(() -> new BigDecimal(em.createNativeQuery(sql).getSingleResult().toString()));
    }

    @Test
    void expiracaoDeCompraParcialLiberaSoORestanteUmaVez() throws Exception {
        UUID id = compraParcial();
        assertThrows(RuntimeException.class, () -> aplicar(expirada(id, id, "CONTA-002", 4, 6, UUID.randomUUID())),
                "causação diferente do encerramento determinístico");
        assertThrows(RuntimeException.class, () -> aplicar(expirada(id, id, "CONTA-002", 4, 5, ENCERRAMENTO)));
        ObjectNode fim = expirada(id, id, "CONTA-002", 4, 6, ENCERRAMENTO);
        aplicar(fim);
        aplicar(fim);
        assertEquals(0, BigDecimal.ZERO.compareTo(decimal("SELECT reservado FROM conta_risco WHERE conta='CONTA-002'")));
        assertEquals(0, new BigDecimal("960").compareTo(decimal("SELECT disponivel FROM conta_risco WHERE conta='CONTA-002'")));
        ObjectNode outra = expirada(id, id, "CONTA-002", 4, 6, ENCERRAMENTO);
        assertThrows(RuntimeException.class, () -> aplicar(outra), "terminal repetido com id novo não libera de novo");
    }

    @Test
    void expiracaoDasPernasOcoLiberaGrupoSoNaUltima() throws Exception {
        UUID[] p = ocoReduzido();
        aplicar(expirada(p[0], p[1], "CONTA-003", 4, 6, ENCERRAMENTO));
        assertEquals(0, new BigDecimal("6").compareTo(decimal(
                "SELECT reservado FROM custodia_risco WHERE conta='CONTA-003' AND ticker='ALFA3'")), "SL ainda vivo");
        assertThrows(RuntimeException.class, () -> aplicar(expirada(p[0], p[2], "CONTA-003", 0, 10, ENCERRAMENTO)),
                "cancelada do SL considera a redução");
        aplicar(expirada(p[0], p[2], "CONTA-003", 0, 6, ENCERRAMENTO));
        assertEquals(0, BigDecimal.ZERO.compareTo(decimal(
                "SELECT reservado FROM custodia_risco WHERE conta='CONTA-003' AND ticker='ALFA3'")));
        assertEquals(0, new BigDecimal("96").compareTo(decimal(
                "SELECT disponivel FROM custodia_risco WHERE conta='CONTA-003' AND ticker='ALFA3'")));
    }

    @Test
    void admissaoTardiaRejeitadaPorSessaoEncerradaLiberaReserva() throws Exception {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            limpar();
            decisao(id, "CONTA-002", "COMPRA", new BigDecimal("100.00"), null);
            em.createNativeQuery("""
                    INSERT INTO reserva(intencao,conta,ticker,recurso,valor,estado,criada_em,ordem,admissao,tipo,validade,
                    data_sessao,quantidade_inicial,quantidade_restante,preco_limite,preco_maximo,valor_restante)
                    VALUES(:i,'CONTA-002','ALFA3','CAIXA',100.00,'ATIVA',now(),:i,:a,'LIMIT','DIA','2026-10-18',10,10,
                    10.00,10.00,100.00)
                    """).setParameter("i", id).setParameter("a", EnvelopeAdmissao.id(id)).executeUpdate();
            em.createNativeQuery("UPDATE conta_risco SET disponivel=900,reservado=100 WHERE conta='CONTA-002'").executeUpdate();
        });
        for (String motivo : new String[]{"SESSAO_ENCERRADA", "SESSAO_NAO_ENCERRADA"}) {
            ObjectNode e = JSON.createObjectNode();
            e.put("id", UUID.randomUUID().toString()); e.put("tipo", "ORDEM_REJEITADA"); e.put("versao", 1);
            e.put("origem", "matching-engine"); e.put("correlacao", EnvelopeAdmissao.id(id).toString());
            e.put("ocorridoEm", "2026-10-18T22:00:00Z"); e.put("ticker", "ALFA3");
            e.put("dataSessao", "2026-10-18"); e.putNull("traceparent");
            ObjectNode d = e.putObject("dados");
            d.put("ordem", id.toString()); d.put("intencao", id.toString()); d.put("conta", "CONTA-002"); d.put("motivo", motivo);
            assertDoesNotThrow(() -> EnvelopeMatching.ler(JSON.writeValueAsBytes(e)), motivo);
            if (motivo.equals("SESSAO_ENCERRADA")) aplicar(e);
        }
        assertEquals(0, BigDecimal.ZERO.compareTo(decimal("SELECT reservado FROM conta_risco WHERE conta='CONTA-002'")));
    }

    private ObjectNode marco(UUID correlacao) {
        ObjectNode e = JSON.createObjectNode();
        e.put("id", UUID.nameUUIDFromBytes(("matching:" + ENCERRAMENTO + ":0").getBytes(StandardCharsets.UTF_8)).toString());
        e.put("tipo", "SESSAO_ENCERRADA"); e.put("versao", 4);
        e.put("origem", "matching-engine"); e.put("correlacao", correlacao.toString());
        e.put("causacao", ENCERRAMENTO.toString());
        e.put("ocorridoEm", "2026-10-18T21:00:00Z"); e.put("ticker", "ALFA3");
        e.put("dataSessao", "2026-10-18"); e.putNull("traceparent");
        e.putObject("dados").put("expiradas", 0);
        return e;
    }

    @Test
    void marcoDoEncerramentoSemContaEDeduplicadoEExigeCorrelacaoIgualACausacao() throws Exception {
        QuarkusTransaction.requiringNew().run(this::limpar);
        ObjectNode m = marco(ENCERRAMENTO);
        aplicar(m);
        aplicar(m);
        assertEquals(1L, ((Number) QuarkusTransaction.requiringNew().call(() ->
                em.createNativeQuery("SELECT count(*) FROM matching_evento").getSingleResult())).longValue());
        assertThrows(RuntimeException.class, () -> aplicar(marco(UUID.randomUUID())));
    }
}
