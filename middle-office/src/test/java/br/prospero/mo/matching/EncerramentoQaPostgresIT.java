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

/**
 * QA independente da RF05c.4a no MO: expiração v4 de stops (pendente e acionado), perna OCO acionada,
 * IOC, identidade da reserva e o marco SESSAO_ENCERRADA. Opt-in como os demais ITs.
 */
@QuarkusTest
@TestProfile(MatchingPostgresIT.Banco.class)
class EncerramentoQaPostgresIT {
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

    /** Venda Stop simples DIA de 10, gatilho 9,00, ainda pendente: custódia reservada 10. */
    private UUID stopVendaPendente() {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            limpar();
            decisao(id, "CONTA-003", "VENDA", null, 10L);
            em.createNativeQuery("""
                    INSERT INTO reserva(intencao,conta,ticker,recurso,quantidade,estado,criada_em,ordem,admissao,tipo,validade,
                    data_sessao,quantidade_inicial,quantidade_restante,preco_gatilho,stop_acionado)
                    VALUES(:i,'CONTA-003','ALFA3','CUSTODIA',10,'ATIVA',now(),:i,:a,'STOP','DIA','2026-10-18',10,10,9.00,FALSE)
                    """).setParameter("i", id).setParameter("a", EnvelopeAdmissao.id(id)).executeUpdate();
            em.createNativeQuery("UPDATE custodia_risco SET disponivel=90,reservado=10 WHERE conta='CONTA-003' AND ticker='ALFA3'")
                    .executeUpdate();
        });
        return id;
    }

    /** Compra Stop Limit DIA de 10 acionada, limite/máximo 10,30, 4 executadas a 10,30: restante 6, valor 61,80. */
    private UUID stopLimitAcionadoNoLivro() {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            limpar();
            decisao(id, "CONTA-002", "COMPRA", new BigDecimal("103.00"), null);
            em.createNativeQuery("""
                    INSERT INTO reserva(intencao,conta,ticker,recurso,valor,estado,criada_em,ordem,admissao,tipo,validade,
                    data_sessao,quantidade_inicial,quantidade_restante,preco_limite,preco_maximo,valor_restante,preco_gatilho,
                    stop_acionado)
                    VALUES(:i,'CONTA-002','ALFA3','CAIXA',103.00,'PARCIAL',now(),:i,:a,'STOP_LIMIT','DIA','2026-10-18',10,6,
                    10.30,10.30,61.80,10.00,TRUE)
                    """).setParameter("i", id).setParameter("a", EnvelopeAdmissao.id(id)).executeUpdate();
            em.createNativeQuery("UPDATE conta_risco SET total=958.80,disponivel=897.00,reservado=61.80 WHERE conta='CONTA-002'")
                    .executeUpdate();
        });
        return id;
    }

    /** Compra Limit de 10 a 10,00 ativa com a validade dada (valor 100). */
    private UUID compraLimit(String validade) {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            limpar();
            decisao(id, "CONTA-002", "COMPRA", new BigDecimal("100.00"), null);
            em.createNativeQuery("""
                    INSERT INTO reserva(intencao,conta,ticker,recurso,valor,estado,criada_em,ordem,admissao,tipo,validade,
                    data_sessao,quantidade_inicial,quantidade_restante,preco_limite,preco_maximo,valor_restante)
                    VALUES(:i,'CONTA-002','ALFA3','CAIXA',100.00,'ATIVA',now(),:i,:a,'LIMIT',:v,'2026-10-18',10,10,
                    10.00,10.00,100.00)
                    """).setParameter("i", id).setParameter("a", EnvelopeAdmissao.id(id)).setParameter("v", validade).executeUpdate();
            em.createNativeQuery("UPDATE conta_risco SET disponivel=900,reservado=100 WHERE conta='CONTA-002'").executeUpdate();
        });
        return id;
    }

    /** Par OCO de venda de 10: TP já cancelado pela irmã, SL Stop Limit acionado e ainda sem execução. */
    private UUID[] ocoSlAcionado() {
        UUID g = UUID.randomUUID(), tp = UUID.randomUUID(), sl = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            limpar();
            decisao(g, "CONTA-003", "VENDA", null, 10L);
            em.createNativeQuery("""
                    INSERT INTO reserva(intencao,conta,ticker,recurso,quantidade,estado,criada_em,admissao,validade,
                    data_sessao,quantidade_inicial,quantidade_restante,oco)
                    VALUES(:i,'CONTA-003','ALFA3','CUSTODIA',10,'ATIVA',now(),:a,'DIA','2026-10-18',10,10,TRUE)
                    """).setParameter("i", g).setParameter("a", EnvelopeAdmissao.id(g)).executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO reserva_perna(ordem,intencao,papel,tipo,preco_limite,preco_gatilho,quantidade,cancelada,estado)
                    VALUES(:o,:i,'TAKE_PROFIT','LIMIT',12.00,NULL,10,10,'ENCERRADA')
                    """).setParameter("o", tp).setParameter("i", g).executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO reserva_perna(ordem,intencao,papel,tipo,preco_limite,preco_gatilho,quantidade,stop_acionado)
                    VALUES(:o,:i,'STOP_LOSS','STOP_LIMIT',8.90,9.00,10,TRUE)
                    """).setParameter("o", sl).setParameter("i", g).executeUpdate();
            em.createNativeQuery("UPDATE custodia_risco SET disponivel=90,reservado=10 WHERE conta='CONTA-003' AND ticker='ALFA3'")
                    .executeUpdate();
        });
        return new UUID[]{g, tp, sl};
    }

    private ObjectNode expirada(UUID intencao, UUID ordem, String conta, long executada, long cancelada) {
        ObjectNode e = JSON.createObjectNode();
        e.put("id", UUID.randomUUID().toString()); e.put("tipo", "ORDEM_ENCERRADA"); e.put("versao", 4);
        e.put("origem", "matching-engine"); e.put("correlacao", EnvelopeAdmissao.id(intencao).toString());
        e.put("causacao", ENCERRAMENTO.toString());
        e.put("ocorridoEm", "2026-10-18T21:00:00Z"); e.put("ticker", "ALFA3");
        e.put("dataSessao", "2026-10-18"); e.putNull("traceparent");
        ObjectNode d = e.putObject("dados");
        d.put("ordem", ordem.toString()); d.put("intencao", intencao.toString()); d.put("conta", conta);
        d.put("motivo", "EXPIRADA_DIA"); d.put("executada", executada); d.put("cancelada", cancelada);
        return e;
    }

    private ObjectNode marco(UUID correlacao, UUID causacao, long expiradas) {
        ObjectNode e = JSON.createObjectNode();
        e.put("id", UUID.nameUUIDFromBytes(("matching:" + causacao + ":" + expiradas).getBytes(StandardCharsets.UTF_8)).toString());
        e.put("tipo", "SESSAO_ENCERRADA"); e.put("versao", 4);
        e.put("origem", "matching-engine"); e.put("correlacao", correlacao.toString());
        e.put("causacao", causacao.toString());
        e.put("ocorridoEm", "2026-10-18T21:00:00Z"); e.put("ticker", "ALFA3");
        e.put("dataSessao", "2026-10-18"); e.putNull("traceparent");
        e.putObject("dados").put("expiradas", expiradas);
        return e;
    }

    private void aplicar(ObjectNode e) throws Exception {
        aplicar.aplicar(EnvelopeMatching.ler(JSON.writeValueAsBytes(e)));
    }

    private BigDecimal decimal(String sql) {
        return QuarkusTransaction.requiringNew().call(() -> new BigDecimal(em.createNativeQuery(sql).getSingleResult().toString()));
    }

    private BigDecimal caixaReservado() {
        return decimal("SELECT reservado FROM conta_risco WHERE conta='CONTA-002'");
    }

    private BigDecimal custodiaReservada() {
        return decimal("SELECT reservado FROM custodia_risco WHERE conta='CONTA-003' AND ticker='ALFA3'");
    }

    private String estado(UUID intencao) {
        return QuarkusTransaction.requiringNew().call(() -> (String) em.createNativeQuery(
                "SELECT estado FROM reserva WHERE intencao=:i").setParameter("i", intencao).getSingleResult());
    }

    @Test
    void stopSimplesPendenteExpiraELiberaACustodiaInteiraUmaVez() throws Exception {
        UUID id = stopVendaPendente();
        assertThrows(RuntimeException.class, () -> aplicar(expirada(id, id, "CONTA-003", 0, 9)), "cancelada != restante");
        ObjectNode fim = expirada(id, id, "CONTA-003", 0, 10);
        aplicar(fim);
        aplicar(fim);
        assertEquals(0, BigDecimal.ZERO.compareTo(custodiaReservada()));
        assertEquals(0, new BigDecimal("100").compareTo(decimal(
                "SELECT disponivel FROM custodia_risco WHERE conta='CONTA-003' AND ticker='ALFA3'")));
        assertEquals("ENCERRADA", estado(id));
        assertThrows(RuntimeException.class, () -> aplicar(expirada(id, id, "CONTA-003", 0, 10)), "id novo após terminal");
    }

    @Test
    void stopLimitAcionadoNoLivroExpiraELiberaSoOValorRestante() throws Exception {
        UUID id = stopLimitAcionadoNoLivro();
        assertThrows(RuntimeException.class, () -> aplicar(expirada(id, id, "CONTA-002", 0, 6)), "executada diverge");
        aplicar(expirada(id, id, "CONTA-002", 4, 6));
        assertEquals(0, BigDecimal.ZERO.compareTo(caixaReservado()));
        assertEquals(0, new BigDecimal("958.80").compareTo(decimal("SELECT disponivel FROM conta_risco WHERE conta='CONTA-002'")));
        assertEquals(0, new BigDecimal("958.80").compareTo(decimal("SELECT total FROM conta_risco WHERE conta='CONTA-002'")));
    }

    @Test
    void expiracaoDeOrdemIocEFokERecusadaSemLiberar() throws Exception {
        for (String validade : new String[]{"IOC", "FOK"}) {
            UUID id = compraLimit(validade);
            assertThrows(RuntimeException.class, () -> aplicar(expirada(id, id, "CONTA-002", 0, 10)), validade);
            assertEquals(0, new BigDecimal("100").compareTo(caixaReservado()), validade);
            assertEquals("ATIVA", estado(id), validade);
        }
    }

    @Test
    void v4ComContaAtivoOuCorrelacaoDeOutraReservaERecusado() throws Exception {
        UUID id = compraLimit("DIA");
        assertThrows(RuntimeException.class, () -> aplicar(expirada(id, id, "CONTA-001", 0, 10)), "conta de outra reserva");
        ObjectNode outroAtivo = expirada(id, id, "CONTA-002", 0, 10);
        outroAtivo.put("ticker", "BETA3");
        outroAtivo.put("causacao", UUID.nameUUIDFromBytes("encerramento:BETA3:2026-10-18".getBytes(StandardCharsets.UTF_8)).toString());
        assertThrows(RuntimeException.class, () -> aplicar(outroAtivo), "ativo diferente da reserva");
        ObjectNode outraData = expirada(id, id, "CONTA-002", 0, 10);
        outraData.put("dataSessao", "2026-10-19");
        outraData.put("causacao", UUID.nameUUIDFromBytes("encerramento:ALFA3:2026-10-19".getBytes(StandardCharsets.UTF_8)).toString());
        assertThrows(RuntimeException.class, () -> aplicar(outraData), "sessão diferente da reserva");
        ObjectNode correlacaoAlheia = expirada(id, id, "CONTA-002", 0, 10);
        correlacaoAlheia.put("correlacao", ENCERRAMENTO.toString());
        assertThrows(RuntimeException.class, () -> aplicar(correlacaoAlheia), "correlação deve ser a admissão do alvo");
        assertEquals(0, new BigDecimal("100").compareTo(caixaReservado()));
        assertEquals("ATIVA", estado(id));
    }

    @Test
    void slAcionadoDeOcoExpiraELiberaOGrupoETpEncerradoNaoExpira() throws Exception {
        UUID[] p = ocoSlAcionado();
        assertThrows(RuntimeException.class, () -> aplicar(expirada(p[0], p[1], "CONTA-003", 0, 0)), "TP já encerrado");
        aplicar(expirada(p[0], p[2], "CONTA-003", 0, 10));
        assertEquals(0, BigDecimal.ZERO.compareTo(custodiaReservada()));
        assertEquals("ENCERRADA", estado(p[0]));
    }

    @Test
    void marcoSessaoEncerradaRegistraUmaVezERecusaIdentidadeDivergente() throws Exception {
        compraLimit("DIA");
        ObjectNode valido = marco(ENCERRAMENTO, ENCERRAMENTO, 0);
        aplicar(valido);
        aplicar(valido);
        assertEquals(0, new BigDecimal("1").compareTo(decimal(
                "SELECT count(*) FROM matching_evento WHERE id='" + valido.get("id").asText() + "'")));
        assertEquals(0, new BigDecimal("100").compareTo(caixaReservado()), "marco não altera reservas");
        assertThrows(RuntimeException.class, () -> aplicar(marco(UUID.randomUUID(), ENCERRAMENTO, 0)), "correlação != causação");
        UUID outro = UUID.nameUUIDFromBytes("encerramento:ALFA3:2026-10-19".getBytes(StandardCharsets.UTF_8));
        assertThrows(RuntimeException.class, () -> aplicar(marco(outro, outro, 0)), "causação de outra data");
        ObjectNode divergente = valido.deepCopy();
        ((ObjectNode) divergente.get("dados")).put("expiradas", 3);
        assertThrows(RuntimeException.class, () -> aplicar(divergente), "mesmo id com conteúdo divergente");
    }
}
