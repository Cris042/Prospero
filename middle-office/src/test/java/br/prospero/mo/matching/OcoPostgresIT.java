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
 * RF05c.3b1: consumo v3 de pares OCO com progresso por perna e liberação única do grupo. Opt-in
 * -Dtest=OcoPostgresIT com o mesmo banco descartável do MatchingPostgresIT; o par é gravado por SQL porque a
 * admissão OCO pelo MO pertence à RF05c.3b2.
 */
@QuarkusTest
@TestProfile(MatchingPostgresIT.Banco.class)
class OcoPostgresIT {
    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterAll
    static void removerSchemaProprio() throws Exception {
        MatchingPostgresIT.removerSchemaProprio();
    }

    @Inject AplicarMatchingActor aplicar;
    @Inject EntityManager em;
    @Inject br.prospero.mo.risco.jpa.RepositorioRiscoJpa risco;

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
        for (String tabela : new String[]{"matching_evento", "matching_negocio", "reserva_perna", "reserva", "decisao_risco"}) {
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

    @Test
    void vendaParcialDoTpReduzSlCapturaCancelaTpESoUltimoTerminalLiberaCustodia() throws Exception {
        Cenario c = venda();
        ObjectNode aceitaTp = fato(c, c.tp(), "CONTA-003", "ORDEM_ACEITA", c.grupo());
        ((ObjectNode) aceitaTp.get("dados")).put("sequencia", 1);
        assertThrows(RuntimeException.class, () -> aplicar(aceitaTp.put("versao", 2)), "par exige v3");
        aplicar(aceitaTp.put("versao", 3));
        ObjectNode pendente = fato(c, c.sl(), "CONTA-003", "STOP_PENDENTE", c.grupo());
        ((ObjectNode) pendente.get("dados")).put("precoGatilho", "9.00");
        aplicar(pendente);

        aplicar(negocioVenda(c, c.tp(), 4, "12.00", 1));
        assertEquals(6, custodiaReservada());
        assertEquals(4, numero("SELECT executada FROM reserva_perna WHERE papel='TAKE_PROFIT'"));
        assertThrows(RuntimeException.class, () -> aplicar(reduzida(c, 4, 5)), "restante diverge do TP");
        aplicar(reduzida(c, 4, 6));
        assertEquals(4, numero("SELECT reduzida FROM reserva_perna WHERE papel='STOP_LOSS'"));
        assertThrows(RuntimeException.class, () -> aplicar(encerrada(c, c.sl(), "CONTA-003", "CANCELADA_OCO", 0, 6)),
                "SL só é cancelado depois do terminal do TP");

        aplicar(encerrada(c, c.tp(), "CONTA-003", "CANCELADA_OCO", 4, 6));
        assertEquals(6, custodiaReservada(), "terminal do TP com SL vivo não libera");
        assertThrows(RuntimeException.class, () -> aplicar(negocioVenda(c, c.sl(), 1, "12.00", 2)), "SL não acionado");
        ObjectNode acionado = fato(c, c.sl(), "CONTA-003", "STOP_ACIONADO", c.contraparte());
        ((ObjectNode) acionado.get("dados")).put("tipoExecutavel", "MARKET").put("sequencia", 5);
        aplicar(acionado);
        aplicar(negocioVenda(c, c.sl(), 2, "11.00", 2));
        assertEquals(4, custodiaReservada());
        ObjectNode fim = encerrada(c, c.sl(), "CONTA-003", "SEM_LIQUIDEZ", 2, 4);
        aplicar(fim);
        aplicar(fim);
        assertEquals(0, custodiaReservada(), "liberação única do restante do grupo");
        assertEquals(94, numero("SELECT total FROM custodia_risco WHERE conta='CONTA-003' AND ticker='ALFA3'"));
        assertEquals(94, numero("SELECT disponivel FROM custodia_risco WHERE conta='CONTA-003' AND ticker='ALFA3'"));
        assertEquals("ENCERRADA", QuarkusTransaction.requiringNew().call(() ->
                em.createNativeQuery("SELECT estado FROM reserva WHERE intencao=:i").setParameter("i", c.grupo()).getSingleResult()));
    }

    @Test
    void compraComTpIntegralDevolveTrocoECancelamentoDoSlEncerraGrupoSemSaldo() throws Exception {
        Cenario c = compra();
        ObjectNode e = envelope(c.contraparte(), c.contraparte(), "NEGOCIO", 3), d = e.putObject("dados");
        d.put("negocio", "ALFA3-2026-10-18-1"); d.put("preco", "8.00"); d.put("quantidade", 10);
        d.put("ordemCompra", c.tp().toString()); d.put("intencaoCompra", c.grupo().toString());
        d.put("contaCompra", "CONTA-002"); d.put("ordemVenda", c.contraparte().toString());
        d.put("intencaoVenda", c.contraparte().toString()); d.put("contaVenda", "CONTA-003"); d.put("agressor", "VENDA");
        aplicar(e);
        assertEquals(0, new BigDecimal("920").compareTo(decimal("SELECT total FROM conta_risco WHERE conta='CONTA-002'")));
        assertEquals(0, BigDecimal.ZERO.compareTo(decimal("SELECT reservado FROM conta_risco WHERE conta='CONTA-002'")));
        assertEquals(0, new BigDecimal("920").compareTo(decimal("SELECT disponivel FROM conta_risco WHERE conta='CONTA-002'")));
        aplicar(encerrada(c, c.tp(), "CONTA-002", "EXECUTADA", 10, 0));
        assertEquals("PARCIAL", QuarkusTransaction.requiringNew().call(() ->
                em.createNativeQuery("SELECT estado FROM reserva WHERE intencao=:i").setParameter("i", c.grupo()).getSingleResult()));
        aplicar(encerrada(c, c.sl(), "CONTA-002", "CANCELADA_OCO", 0, 10));
        assertEquals(0, new BigDecimal("920").compareTo(decimal("SELECT disponivel FROM conta_risco WHERE conta='CONTA-002'")));
        assertEquals("ENCERRADA", QuarkusTransaction.requiringNew().call(() ->
                em.createNativeQuery("SELECT estado FROM reserva WHERE intencao=:i").setParameter("i", c.grupo()).getSingleResult()));
    }

    @Test
    void rejeicaoDoParLiberaSomenteNaSegundaPernaEGuardasRecusamForjas() throws Exception {
        Cenario c = venda();
        ObjectNode reducaoTp = fato(c, c.tp(), "CONTA-003", "ORDEM_REDUZIDA", c.grupo());
        ((ObjectNode) reducaoTp.get("dados")).put("reduzida", 1).put("restante", 9);
        assertThrows(RuntimeException.class, () -> aplicar(reducaoTp), "redução só no SL");
        assertThrows(RuntimeException.class, () -> aplicar(encerrada(c, c.sl(), "CONTA-003", "LIMITE_CASCATA", 0, 10)),
                "cascata exige TP já encerrado na captura");
        ObjectNode rejTp = fato(c, c.tp(), "CONTA-003", "ORDEM_REJEITADA", c.grupo());
        ((ObjectNode) rejTp.get("dados")).put("motivo", "LIMITE_ORDENS_ATIVAS");
        aplicar(rejTp);
        assertEquals(10, custodiaReservada(), "irmã viva mantém a reserva");
        ObjectNode rejSl = fato(c, c.sl(), "CONTA-003", "ORDEM_REJEITADA", c.grupo());
        ((ObjectNode) rejSl.get("dados")).put("motivo", "LIMITE_ORDENS_ATIVAS");
        aplicar(rejSl);
        aplicar(rejSl);
        assertEquals(0, custodiaReservada());
        assertEquals(100, numero("SELECT disponivel FROM custodia_risco WHERE conta='CONTA-003' AND ticker='ALFA3'"));
    }

    @Test
    void reservaOcoSemPernasContinuaLegadaEEventoV3DeOrdemSimplesERecusado() throws Exception {
        Cenario c = venda();
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("DELETE FROM reserva_perna").executeUpdate());
        ObjectNode aceita = fato(c, c.tp(), "CONTA-003", "ORDEM_ACEITA", c.grupo());
        ((ObjectNode) aceita.get("dados")).put("sequencia", 1);
        assertThrows(RuntimeException.class, () -> aplicar(aceita));
        ObjectNode simples = envelope(c.contraparte(), c.contraparte(), "ORDEM_ACEITA", 3), d = simples.putObject("dados");
        d.put("ordem", c.contraparte().toString()); d.put("intencao", c.contraparte().toString());
        d.put("conta", "CONTA-002"); d.put("sequencia", 2);
        assertThrows(RuntimeException.class, () -> aplicar(simples), "v3 sem OCO envolvido");
        assertEquals(0, numero("SELECT count(*) FROM matching_evento"));
    }

    @Test
    void admissaoRealDoParGravaGrupoEPernasAtomicamenteEPublicaV3() throws Exception {
        QuarkusTransaction.requiringNew().run(this::limpar);
        var dia = br.prospero.mo.risco.dominio.Validade.DIA;
        var tp = new br.prospero.mo.risco.dominio.Perna(UUID.randomUUID(), br.prospero.mo.risco.dominio.TipoOrdem.LIMIT, 2,
                new BigDecimal("9.80"), null, dia);
        var sl = new br.prospero.mo.risco.dominio.Perna(UUID.randomUUID(), br.prospero.mo.risco.dominio.TipoOrdem.STOP, 2,
                null, new BigDecimal("10.20"), dia);
        UUID grupo = UUID.randomUUID();
        var par = new br.prospero.mo.risco.dominio.ComandoRisco(UUID.randomUUID(), grupo, "CONTA-002", "ALFA3",
                br.prospero.mo.risco.dominio.Lado.COMPRA, new BigDecimal("10.00"), java.util.List.of(tp, sl), null,
                java.time.LocalDate.of(2026, 10, 18));
        // Em teste o actor usa o repositório em memória; aqui a decisão e a admissão passam pelo JPA real.
        var decisao = br.prospero.mo.risco.dominio.Decisao.reservada(par,
                br.prospero.mo.risco.helper.CalculoReserva.exigencia(par, risco.parametros("ALFA3").orElseThrow()), 1,
                java.time.Instant.parse("2026-10-18T12:00:00Z"));
        QuarkusTransaction.requiringNew().run(() -> {
            risco.bloquearConta("CONTA-002");
            risco.registrar(decisao);
            risco.registrarAdmissao(par, decisao);
            // A movimentação do disponível pertence ao actor (fora deste IT); reproduzida aqui com o mesmo valor.
            em.createNativeQuery("UPDATE conta_risco SET disponivel=disponivel-22.44,reservado=22.44 WHERE conta='CONTA-002'")
                    .executeUpdate();
        });
        assertEquals(0, new BigDecimal("22.44").compareTo(decimal("SELECT reservado FROM conta_risco WHERE conta='CONTA-002'")));
        Object[] linha = QuarkusTransaction.requiringNew().call(() -> (Object[]) em.createNativeQuery(
                "SELECT oco,ordem,tipo,preco_maximo,valor_restante,quantidade_restante FROM reserva WHERE intencao=:i")
                .setParameter("i", grupo).getSingleResult());
        assertEquals(Boolean.TRUE, linha[0]);
        assertNull(linha[1]);
        assertNull(linha[2]);
        assertEquals(0, new BigDecimal("11.22").compareTo((BigDecimal) linha[3]));
        assertEquals(2, numero("SELECT count(*) FROM reserva_perna WHERE intencao='" + grupo + "'"));
        var json = JSON.readTree(EnvelopeAdmissao.serializar(par, decisao, null));
        assertEquals(3, json.get("versao").intValue());
        assertEquals(EnvelopeAdmissao.id(grupo).toString(), json.get("id").textValue());
        Cenario c = new Cenario(grupo, tp.id(), sl.id(), UUID.randomUUID());
        ObjectNode aceita = fato(c, tp.id(), "CONTA-002", "ORDEM_ACEITA", grupo);
        ((ObjectNode) aceita.get("dados")).put("sequencia", 1);
        aplicar(aceita);
        aplicar(encerrada(c, tp.id(), "CONTA-002", "LIMITE_NEGOCIOS", 0, 2));
        assertEquals(0, new BigDecimal("22.44").compareTo(decimal("SELECT reservado FROM conta_risco WHERE conta='CONTA-002'")),
                "terminal do TP com SL vivo mantém a reserva do grupo");
        aplicar(encerrada(c, sl.id(), "CONTA-002", "CANCELADA_OCO", 0, 2));
        assertEquals(0, BigDecimal.ZERO.compareTo(decimal("SELECT reservado FROM conta_risco WHERE conta='CONTA-002'")));
        assertEquals(0, new BigDecimal("1000").compareTo(decimal("SELECT disponivel FROM conta_risco WHERE conta='CONTA-002'")));
    }
}
