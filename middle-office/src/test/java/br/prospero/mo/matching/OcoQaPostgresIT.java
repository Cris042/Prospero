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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA independente da RF05c.3b1: ordens de chegada, terminais reentregues, rejeição isolada, cascata, compra com
 * troco e legado. Opt-in -Dtest=OcoQaPostgresIT com o banco descartável do MatchingPostgresIT.
 */
@QuarkusTest
@TestProfile(MatchingPostgresIT.Banco.class)
class OcoQaPostgresIT {
    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterAll
    static void removerSchemaProprio() throws Exception {
        MatchingPostgresIT.removerSchemaProprio();
    }

    @Inject AplicarMatchingActor aplicar;
    @Inject EntityManager em;

    /** Grupo OCO e contraparte simples. */
    private record Cenario(UUID grupo, UUID tp, UUID sl, UUID contraparte) { }

    private Cenario venda() {
        Cenario c = new Cenario(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        QuarkusTransaction.requiringNew().run(() -> {
            limpar();
            decisao(c.grupo(), "CONTA-003", "VENDA", null, 10L);
            em.createNativeQuery("""
                    INSERT INTO reserva(intencao,conta,ticker,recurso,quantidade,estado,criada_em,admissao,validade,
                    data_sessao,quantidade_inicial,quantidade_restante,oco)
                    VALUES(:i,'CONTA-003','ALFA3','CUSTODIA',10,'ATIVA',now(),:a,'DIA','2026-10-18',10,10,TRUE)
                    """).setParameter("i", c.grupo()).setParameter("a", EnvelopeAdmissao.id(c.grupo())).executeUpdate();
            perna(c.tp(), c.grupo(), "TAKE_PROFIT", "LIMIT", "12.00", null);
            perna(c.sl(), c.grupo(), "STOP_LOSS", "STOP", null, "9.00");
            simples(c.contraparte(), "CONTA-002", "COMPRA", "12.00", "120.00");
            em.createNativeQuery("UPDATE custodia_risco SET disponivel=90,reservado=10 WHERE conta='CONTA-003' AND ticker='ALFA3'").executeUpdate();
            em.createNativeQuery("UPDATE conta_risco SET disponivel=880,reservado=120 WHERE conta='CONTA-002'").executeUpdate();
        });
        return c;
    }

    private Cenario compra() {
        Cenario c = new Cenario(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        QuarkusTransaction.requiringNew().run(() -> {
            limpar();
            decisao(c.grupo(), "CONTA-002", "COMPRA", new BigDecimal("110.00"), null);
            em.createNativeQuery("""
                    INSERT INTO reserva(intencao,conta,ticker,recurso,valor,estado,criada_em,admissao,validade,
                    data_sessao,quantidade_inicial,quantidade_restante,preco_maximo,valor_restante,oco)
                    VALUES(:i,'CONTA-002','ALFA3','CAIXA',110.00,'ATIVA',now(),:a,'DIA','2026-10-18',10,10,11.00,110.00,TRUE)
                    """).setParameter("i", c.grupo()).setParameter("a", EnvelopeAdmissao.id(c.grupo())).executeUpdate();
            perna(c.tp(), c.grupo(), "TAKE_PROFIT", "LIMIT", "8.00", null);
            perna(c.sl(), c.grupo(), "STOP_LOSS", "STOP", null, "10.00");
            simples(c.contraparte(), "CONTA-003", "VENDA", "8.00", null);
            em.createNativeQuery("UPDATE conta_risco SET disponivel=890,reservado=110 WHERE conta='CONTA-002'").executeUpdate();
            em.createNativeQuery("UPDATE custodia_risco SET disponivel=90,reservado=10 WHERE conta='CONTA-003' AND ticker='ALFA3'").executeUpdate();
        });
        return c;
    }

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

    private void perna(UUID ordem, UUID grupo, String papel, String tipo, String limite, String gatilho) {
        em.createNativeQuery("""
                INSERT INTO reserva_perna(ordem,intencao,papel,tipo,preco_limite,preco_gatilho,quantidade)
                VALUES(:o,:i,:p,:t,:l,:g,10)
                """).unwrap(org.hibernate.query.NativeQuery.class).setParameter("o", ordem).setParameter("i", grupo)
                .setParameter("p", papel).setParameter("t", tipo)
                .setParameter("l", limite == null ? null : new BigDecimal(limite), BigDecimal.class)
                .setParameter("g", gatilho == null ? null : new BigDecimal(gatilho), BigDecimal.class).executeUpdate();
    }

    private void simples(UUID id, String conta, String lado, String limite, String valor) {
        boolean compra = lado.equals("COMPRA");
        decisao(id, conta, lado, compra ? new BigDecimal(valor) : null, compra ? null : 10L);
        em.createNativeQuery("""
                INSERT INTO reserva(intencao,conta,ticker,recurso,valor,quantidade,estado,criada_em,
                ordem,admissao,tipo,validade,data_sessao,quantidade_inicial,quantidade_restante,
                preco_limite,preco_maximo,valor_restante)
                VALUES(:i,:c,'ALFA3',:r,:v,:q,'ATIVA',now(),:i,:a,'LIMIT','DIA','2026-10-18',10,10,:p,:t,:v)
                """).unwrap(org.hibernate.query.NativeQuery.class).setParameter("i", id).setParameter("c", conta)
                .setParameter("r", compra ? "CAIXA" : "CUSTODIA")
                .setParameter("v", compra ? new BigDecimal(valor) : null, BigDecimal.class)
                .setParameter("q", compra ? null : 10L, Long.class).setParameter("a", EnvelopeAdmissao.id(id))
                .setParameter("p", new BigDecimal(limite))
                .setParameter("t", compra ? new BigDecimal(limite) : null, BigDecimal.class).executeUpdate();
    }

    private ObjectNode envelope(UUID correlacao, UUID causacao, String tipo, int versao) {
        ObjectNode e = JSON.createObjectNode();
        e.put("id", UUID.randomUUID().toString()); e.put("tipo", tipo); e.put("versao", versao);
        e.put("origem", "matching-engine"); e.put("correlacao", EnvelopeAdmissao.id(correlacao).toString());
        e.put("ocorridoEm", "2026-10-18T12:00:00Z"); e.put("ticker", "ALFA3");
        e.put("dataSessao", "2026-10-18"); e.putNull("traceparent");
        if (versao > 1) e.put("causacao", EnvelopeAdmissao.id(causacao).toString());
        return e;
    }

    private ObjectNode fato(Cenario c, UUID ordem, String conta, String tipo, UUID causacao) {
        ObjectNode e = envelope(c.grupo(), causacao, tipo, 3), d = e.putObject("dados");
        d.put("ordem", ordem.toString()); d.put("intencao", c.grupo().toString()); d.put("conta", conta);
        return e;
    }

    private ObjectNode encerrada(Cenario c, UUID ordem, String conta, String motivo, long executada, long cancelada) {
        ObjectNode e = fato(c, ordem, conta, "ORDEM_ENCERRADA", c.grupo());
        ObjectNode d = (ObjectNode) e.get("dados");
        d.put("motivo", motivo); d.put("executada", executada); d.put("cancelada", cancelada);
        return e;
    }

    private ObjectNode reduzida(Cenario c, long reduzida, long restante) {
        ObjectNode e = fato(c, c.sl(), "CONTA-003", "ORDEM_REDUZIDA", c.contraparte());
        ((ObjectNode) e.get("dados")).put("reduzida", reduzida).put("restante", restante);
        return e;
    }

    private ObjectNode negocioVenda(Cenario c, UUID ordemVenda, int q, String preco, int n) {
        ObjectNode e = envelope(c.contraparte(), c.contraparte(), "NEGOCIO", 3), d = e.putObject("dados");
        d.put("negocio", "ALFA3-2026-10-18-" + n); d.put("preco", preco); d.put("quantidade", q);
        d.put("ordemCompra", c.contraparte().toString()); d.put("intencaoCompra", c.contraparte().toString());
        d.put("contaCompra", "CONTA-002"); d.put("ordemVenda", ordemVenda.toString());
        d.put("intencaoVenda", c.grupo().toString()); d.put("contaVenda", "CONTA-003"); d.put("agressor", "COMPRA");
        return e;
    }

    private void aplicar(ObjectNode e) throws Exception {
        aplicar.aplicar(EnvelopeMatching.ler(JSON.writeValueAsBytes(e)));
    }

    private long numero(String sql) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(sql).getSingleResult()).longValue());
    }

    private BigDecimal decimal(String sql) {
        return QuarkusTransaction.requiringNew().call(() -> new BigDecimal(em.createNativeQuery(sql).getSingleResult().toString()));
    }

    private long custodiaReservada() {
        return numero("SELECT reservado FROM custodia_risco WHERE conta='CONTA-003' AND ticker='ALFA3'");
    }


    private ObjectNode comMotivo(ObjectNode e, String motivo) { ((ObjectNode) e.get("dados")).put("motivo", motivo); return e; }

    private ObjectNode negocioTpAgressorVenda(Cenario c, int q) {
        ObjectNode e = envelope(c.grupo(), c.grupo(), "NEGOCIO", 3), d = e.putObject("dados");
        d.put("negocio", "ALFA3-2026-10-18-1"); d.put("preco", "12.00"); d.put("quantidade", q);
        d.put("ordemCompra", c.contraparte().toString()); d.put("intencaoCompra", c.contraparte().toString());
        d.put("contaCompra", "CONTA-002"); d.put("ordemVenda", c.tp().toString());
        d.put("intencaoVenda", c.grupo().toString()); d.put("contaVenda", "CONTA-003"); d.put("agressor", "VENDA");
        return e;
    }

    private ObjectNode reduzidaRaiz(Cenario c, UUID raiz, long reduzida, long restante) {
        ObjectNode e = fato(c, c.sl(), "CONTA-003", "ORDEM_REDUZIDA", raiz);
        ((ObjectNode) e.get("dados")).put("reduzida", reduzida).put("restante", restante);
        return e;
    }

    private ObjectNode terminal(Cenario c, UUID ordem, String conta, String motivo, long executada, long cancelada, UUID raiz) {
        ObjectNode e = fato(c, ordem, conta, "ORDEM_ENCERRADA", raiz), d = (ObjectNode) e.get("dados");
        d.put("motivo", motivo); d.put("executada", executada); d.put("cancelada", cancelada);
        return e;
    }

    private String estadoGrupo(UUID grupo) {
        return QuarkusTransaction.requiringNew().call(() -> (String) em.createNativeQuery(
                "SELECT estado FROM reserva WHERE intencao=:i").setParameter("i", grupo).getSingleResult());
    }

    @Test
    void reducaoAntesDoStopPendenteNaAdmissaoComTpAgressor() throws Exception {
        Cenario c = venda();
        ObjectNode aceitaTp = fato(c, c.tp(), "CONTA-003", "ORDEM_ACEITA", c.grupo());
        ((ObjectNode) aceitaTp.get("dados")).put("sequencia", 1);
        ObjectNode aceitaSl = fato(c, c.sl(), "CONTA-003", "ORDEM_ACEITA", c.grupo());
        ((ObjectNode) aceitaSl.get("dados")).put("sequencia", 2);
        aplicar(aceitaTp); aplicar(aceitaSl);
        aplicar(negocioTpAgressorVenda(c, 4));
        aplicar(reduzidaRaiz(c, c.grupo(), 4, 6));
        ObjectNode oferta = fato(c, c.tp(), "CONTA-003", "OFERTA_NO_LIVRO", c.grupo());
        ((ObjectNode) oferta.get("dados")).put("lado", "VENDA").put("preco", "12.00").put("restante", 6);
        aplicar(oferta);
        ObjectNode pendente = fato(c, c.sl(), "CONTA-003", "STOP_PENDENTE", c.grupo());
        ((ObjectNode) pendente.get("dados")).put("precoGatilho", "9.00");
        aplicar(pendente);
        assertEquals(6, custodiaReservada());
        assertEquals(4, numero("SELECT reduzida FROM reserva_perna WHERE papel='STOP_LOSS'"));
        assertEquals(4, numero("SELECT executada FROM reserva_perna WHERE papel='TAKE_PROFIT'"));
    }

    @Test
    void reducaoCoerenteComSlMasSemExecucaoDoTpERecusada() throws Exception {
        Cenario c = venda();
        assertThrows(RuntimeException.class, () -> aplicar(reduzidaRaiz(c, c.contraparte(), 4, 6)), "TP ainda deve 10");
        assertEquals(0, numero("SELECT reduzida FROM reserva_perna WHERE papel='STOP_LOSS'"));
        assertEquals(0, numero("SELECT count(*) FROM matching_evento"));
    }

    @Test
    void cascataDoSlComTpJaCanceladoLiberaUmaVezETerminaisComIdNovoSaoRecusados() throws Exception {
        Cenario c = venda();
        ObjectNode cancelTp = terminal(c, c.tp(), "CONTA-003", "CANCELADA_OCO", 0, 10, c.contraparte());
        aplicar(cancelTp);
        assertEquals(10, custodiaReservada(), "TP cancelado com SL vivo não libera");
        ObjectNode novoIdTp = terminal(c, c.tp(), "CONTA-003", "CANCELADA_OCO", 0, 10, c.contraparte());
        assertThrows(RuntimeException.class, () -> aplicar(novoIdTp), "terminal do TP com id novo");
        aplicar(terminal(c, c.sl(), "CONTA-003", "LIMITE_CASCATA", 0, 10, c.contraparte()));
        assertEquals(0, custodiaReservada());
        assertEquals(100, numero("SELECT disponivel FROM custodia_risco WHERE conta='CONTA-003' AND ticker='ALFA3'"));
        assertThrows(RuntimeException.class,
                () -> aplicar(terminal(c, c.sl(), "CONTA-003", "LIMITE_CASCATA", 0, 10, c.contraparte())), "SL com id novo");
        assertThrows(RuntimeException.class,
                () -> aplicar(terminal(c, c.tp(), "CONTA-003", "CANCELADA_OCO", 0, 10, c.contraparte())), "TP com id novo após liberação");
        assertEquals(100, numero("SELECT disponivel FROM custodia_risco WHERE conta='CONTA-003' AND ticker='ALFA3'"), "sem dupla liberação");
        assertEquals(0, custodiaReservada());
        assertEquals("ENCERRADA", estadoGrupo(c.grupo()));
    }

    @Test
    void rejeicaoSoDoSlMantemReservaETpExecutadoEncerraGrupo() throws Exception {
        Cenario c = venda();
        ObjectNode aceitaTp = fato(c, c.tp(), "CONTA-003", "ORDEM_ACEITA", c.grupo());
        ((ObjectNode) aceitaTp.get("dados")).put("sequencia", 1);
        aplicar(aceitaTp);
        aplicar(comMotivo(fato(c, c.sl(), "CONTA-003", "ORDEM_REJEITADA", c.grupo()), "COMBINACAO_INVALIDA"));
        assertEquals(10, custodiaReservada(), "TP vivo mantém a reserva do grupo");
        aplicar(negocioVenda(c, c.tp(), 10, "12.00", 1));
        aplicar(encerrada(c, c.tp(), "CONTA-003", "EXECUTADA", 10, 0));
        assertEquals(0, custodiaReservada());
        assertEquals(90, numero("SELECT total FROM custodia_risco WHERE conta='CONTA-003' AND ticker='ALFA3'"));
        assertEquals(90, numero("SELECT disponivel FROM custodia_risco WHERE conta='CONTA-003' AND ticker='ALFA3'"));
        assertEquals("ENCERRADA", estadoGrupo(c.grupo()));
    }

    @Test
    void limiteDePendentesNasDuasPernasLiberaNaSegunda() throws Exception {
        Cenario c = venda();
        aplicar(comMotivo(fato(c, c.tp(), "CONTA-003", "ORDEM_REJEITADA", c.grupo()), "LIMITE_STOPS_PENDENTES"));
        assertEquals(10, custodiaReservada());
        aplicar(comMotivo(fato(c, c.sl(), "CONTA-003", "ORDEM_REJEITADA", c.grupo()), "LIMITE_STOPS_PENDENTES"));
        assertEquals(0, custodiaReservada());
        assertEquals(100, numero("SELECT disponivel FROM custodia_risco WHERE conta='CONTA-003' AND ticker='ALFA3'"));
    }

    @Test
    void negocioDoTpAposCapturaERecusado() throws Exception {
        Cenario c = venda();
        aplicar(terminal(c, c.tp(), "CONTA-003", "CANCELADA_OCO", 0, 10, c.contraparte()));
        ObjectNode acionado = fato(c, c.sl(), "CONTA-003", "STOP_ACIONADO", c.contraparte());
        ((ObjectNode) acionado.get("dados")).put("tipoExecutavel", "MARKET").put("sequencia", 5);
        aplicar(acionado);
        assertThrows(RuntimeException.class, () -> aplicar(negocioVenda(c, c.tp(), 1, "12.00", 1)), "TP já capturado");
        assertEquals(10, custodiaReservada());
    }

    @Test
    void compraComSlCapturadoConsomePeloPrecoMaximoDevolveTrocoELiberaSoNoUltimoTerminal() throws Exception {
        Cenario c = compra();
        aplicar(terminal(c, c.tp(), "CONTA-002", "CANCELADA_OCO", 0, 10, c.contraparte()));
        assertEquals(0, new BigDecimal("110").compareTo(decimal("SELECT reservado FROM conta_risco WHERE conta='CONTA-002'")));
        ObjectNode acionado = fato(c, c.sl(), "CONTA-002", "STOP_ACIONADO", c.contraparte());
        ((ObjectNode) acionado.get("dados")).put("tipoExecutavel", "MARKET").put("sequencia", 5);
        aplicar(acionado);
        ObjectNode e = envelope(c.grupo(), c.contraparte(), "NEGOCIO", 3), d = e.putObject("dados");
        d.put("negocio", "ALFA3-2026-10-18-1"); d.put("preco", "10.50"); d.put("quantidade", 6);
        d.put("ordemCompra", c.sl().toString()); d.put("intencaoCompra", c.grupo().toString());
        d.put("contaCompra", "CONTA-002"); d.put("ordemVenda", c.contraparte().toString());
        d.put("intencaoVenda", c.contraparte().toString()); d.put("contaVenda", "CONTA-003"); d.put("agressor", "COMPRA");
        aplicar(e);
        assertEquals(0, new BigDecimal("44.00").compareTo(decimal("SELECT reservado FROM conta_risco WHERE conta='CONTA-002'")));
        assertEquals(0, new BigDecimal("937.00").compareTo(decimal("SELECT total FROM conta_risco WHERE conta='CONTA-002'")));
        aplicar(terminal(c, c.sl(), "CONTA-002", "SEM_LIQUIDEZ", 6, 4, c.grupo()));
        assertEquals(0, BigDecimal.ZERO.compareTo(decimal("SELECT reservado FROM conta_risco WHERE conta='CONTA-002'")));
        assertEquals(0, new BigDecimal("937.00").compareTo(decimal("SELECT disponivel FROM conta_risco WHERE conta='CONTA-002'")));
    }

    @Test
    void reservaOcoLegadaSemPernasRecusaEventosEmTodasAsVersoes() throws Exception {
        Cenario c = venda();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM reserva_perna").executeUpdate();
            em.createNativeQuery("UPDATE reserva SET oco=FALSE WHERE intencao=:i").setParameter("i", c.grupo()).executeUpdate();
        });
        for (int versao = 1; versao <= 3; versao++) {
            ObjectNode aceita = fato(c, c.tp(), "CONTA-003", "ORDEM_ACEITA", c.grupo());
            ((ObjectNode) aceita.get("dados")).put("sequencia", 1);
            aceita.put("versao", versao);
            if (versao == 1) aceita.remove("causacao");
            assertThrows(RuntimeException.class, () -> aplicar(aceita), "legado v" + versao);
        }
        assertEquals(0, numero("SELECT count(*) FROM matching_evento"));
        assertEquals(10, custodiaReservada());
    }

    @Test
    void migracaoV5PreservaLegadoComoNaoOcoEConstraintsDasPernas() throws Exception {
        String proprio = "upgrade_oco_" + UUID.randomUUID().toString().replace("-", "");
        UUID id = UUID.randomUUID();
        String url = System.getenv("MATCHING_TEST_DB_URL"), usuario = System.getenv("MATCHING_TEST_DB_USER"),
                senha = System.getenv("MATCHING_TEST_DB_PASSWORD");
        var configuracao = org.flywaydb.core.Flyway.configure().dataSource(url, usuario, senha)
                .schemas(proprio).defaultSchema(proprio).locations("classpath:db/migration");
        try {
            configuracao.target("4").load().migrate();
            try (var c = java.sql.DriverManager.getConnection(url, usuario, senha); var s = c.createStatement()) {
                c.setSchema(proprio);
                s.execute("INSERT INTO decisao_risco(intencao,evento,conta,ticker,lado,resultado,quantidade_reservada,versao_parametros,decidida_em) VALUES('"
                        + id + "','" + UUID.randomUUID() + "','CONTA-003','ALFA3','VENDA','RESERVADA',10,1,now())");
                s.execute("INSERT INTO reserva(intencao,conta,ticker,recurso,quantidade,estado,criada_em) VALUES('"
                        + id + "','CONTA-003','ALFA3','CUSTODIA',10,'ATIVA',now())");
            }
            configuracao.target("latest").load().migrate();
            try (var c = java.sql.DriverManager.getConnection(url, usuario, senha); var s = c.createStatement()) {
                c.setSchema(proprio);
                try (var r = s.executeQuery("SELECT oco,ordem,estado,quantidade FROM reserva WHERE intencao='" + id + "'")) {
                    assertTrue(r.next()); assertFalse(r.getBoolean(1)); assertNull(r.getObject(2));
                    assertEquals("ATIVA", r.getString(3)); assertEquals(10, r.getLong(4));
                }
                try (var r = s.executeQuery("SELECT count(*) FROM reserva_perna")) { assertTrue(r.next()); assertEquals(0, r.getInt(1)); }
                assertThrows(java.sql.SQLException.class, () -> s.execute(
                        "UPDATE reserva SET oco=TRUE,ordem='" + UUID.randomUUID() + "' WHERE intencao='" + id + "'"), "OCO com ordem");
                assertThrows(java.sql.SQLException.class, () -> s.execute(
                        "INSERT INTO reserva_perna(ordem,intencao,papel,tipo,preco_limite,quantidade,reduzida) VALUES('"
                                + UUID.randomUUID() + "','" + id + "','TAKE_PROFIT','LIMIT',12,10,1)"), "TP reduzido");
                assertThrows(java.sql.SQLException.class, () -> s.execute(
                        "INSERT INTO reserva_perna(ordem,intencao,papel,tipo,preco_limite,preco_gatilho,quantidade) VALUES('"
                                + UUID.randomUUID() + "','" + id + "','STOP_LOSS','STOP',9,9,10)"), "Stop com limite");
                assertThrows(java.sql.SQLException.class, () -> s.execute(
                        "INSERT INTO reserva_perna(ordem,intencao,papel,tipo,preco_gatilho,quantidade,executada,reduzida) VALUES('"
                                + UUID.randomUUID() + "','" + id + "','STOP_LOSS','STOP',9,10,6,5)"), "conservação");
            }
        } finally {
            try (var c = java.sql.DriverManager.getConnection(url, usuario, senha); var s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + proprio + " CASCADE");
            }
        }
    }
}
