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
    @Inject br.prospero.mo.mensageria.jpa.OutboxJpa outbox;
    @Inject br.prospero.mo.risco.jpa.RepositorioRiscoJpa risco;

    private record Par(UUID compra, UUID venda) { }

    private Par preparar() {
        Par par = new Par(UUID.randomUUID(), UUID.randomUUID());
        QuarkusTransaction.requiringNew().run(() -> {
            // RF08c: o mesmo id de negócio volta a cada teste; a expectativa acompanha a deduplicação apagada.
            for (String tabela : new String[]{"liquidacao_expectativa", "liquidacao_aplicada", "liquidacao_aguardando", "liquidacao_recusada"}) {
                em.createNativeQuery("DELETE FROM " + tabela).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM matching_evento").executeUpdate();
            em.createNativeQuery("DELETE FROM matching_negocio").executeUpdate();
            // RF10c1: recibo referencia a reserva; limpeza restrita ao schema descartável do teste.
            em.createNativeQuery("DELETE FROM admissao_publicacao").executeUpdate();
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
    private void tornarStop(Par par, String tipo) {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("UPDATE reserva SET tipo=:t,validade='DIA',preco_gatilho=10,stop_acionado=false,preco_limite=:p WHERE intencao=:i")
                    .unwrap(org.hibernate.query.NativeQuery.class).setParameter("t",tipo)
                    .setParameter("p",tipo.equals("STOP") ? null : new BigDecimal("10.00"),BigDecimal.class)
                    .setParameter("i",par.compra()).executeUpdate();
        });
    }
    private ObjectNode eventoStop(Par par,String tipo,String campos,UUID raiz) throws Exception {
        ObjectNode e=envelope(par.compra(),tipo); e.put("versao",2);
        e.put("causacao",EnvelopeAdmissao.id(raiz).toString());
        ObjectNode d=e.putObject("dados"); d.put("ordem",par.compra().toString());
        d.put("intencao",par.compra().toString()); d.put("conta","CONTA-002");
        d.setAll((ObjectNode)JSON.readTree("{"+campos+"}")); return e;
    }
    private ObjectNode negocioStop(Par par) {
        ObjectNode e=negocio(par); e.put("versao",2); e.put("causacao",EnvelopeAdmissao.id(par.venda()).toString()); return e;
    }
    @Test void stopPersisteAcionamentoSemMoverRecursoENegocioAntesDeAcionarNaoDeduplica() throws Exception {
        Par par=preparar(); tornarStop(par,"STOP");
        var pendente=eventoStop(par,"STOP_PENDENTE","\"precoGatilho\":\"10.00\"",par.compra());
        aplicar(pendente); aplicar(pendente);
        saldo("total","1000"); saldo("reservado","100"); saldo("disponivel","900");
        assertThrows(RuntimeException.class,()->aplicar(negocioStop(par)));
        assertEquals(1,valor("SELECT count(*) FROM matching_evento").intValueExact());
        assertEquals(0,valor("SELECT count(*) FROM matching_negocio").intValueExact());
        assertEquals(Boolean.FALSE,QuarkusTransaction.requiringNew().call(()->em.createNativeQuery("SELECT stop_acionado FROM reserva WHERE intencao=:i").setParameter("i",par.compra()).getSingleResult()));
        assertThrows(RuntimeException.class,()->QuarkusTransaction.requiringNew().run(()->risco.preparar("CONTA-002",new BigDecimal("5000"),Map.of())));
        var acionar=eventoStop(par,"STOP_ACIONADO","\"tipoExecutavel\":\"MARKET\",\"sequencia\":3",par.venda());
        aplicar(acionar); aplicar(acionar);
        assertEquals(Boolean.TRUE,QuarkusTransaction.requiringNew().call(()->em.createNativeQuery("SELECT stop_acionado FROM reserva WHERE intencao=:i").setParameter("i",par.compra()).getSingleResult()));
        saldo("reservado","100"); saldo("disponivel","900");
        ObjectNode negocio=negocioStop(par); aplicar(negocio); aplicar(negocio);
        negocio.put("id",UUID.randomUUID().toString()); aplicar(negocio);
        saldo("total","964"); saldo("reservado","60"); saldo("disponivel","904");
        var terminal=eventoStop(par,"ORDEM_ENCERRADA","\"motivo\":\"SALDO_CANCELADO_IOC\",\"executada\":4,\"cancelada\":6",par.venda());
        aplicar(terminal); aplicar(terminal);
        saldo("total","964"); saldo("reservado","0"); saldo("disponivel","964");
        assertEquals(1,valor("SELECT count(*) FROM matching_negocio").intValueExact());
        var adulterado=terminal.deepCopy(); ((ObjectNode)adulterado.get("dados")).put("cancelada",5);
        assertThrows(RuntimeException.class,()->aplicar(adulterado)); saldo("disponivel","964");
    }
    @Test void stopLimitAtivadoPodeRepousarMasOfertaAntesDoAcionamentoEProibida() throws Exception {
        Par par=preparar(); tornarStop(par,"STOP_LIMIT");
        var oferta=eventoStop(par,"OFERTA_NO_LIVRO","\"lado\":\"COMPRA\",\"preco\":\"10.00\",\"restante\":10",par.venda());
        assertThrows(RuntimeException.class,()->aplicar(oferta));
        aplicar(eventoStop(par,"STOP_ACIONADO","\"tipoExecutavel\":\"LIMIT\",\"sequencia\":3",par.venda()));
        aplicar(oferta); saldo("reservado","100"); saldo("total","1000");
    }
    @Test void limiteCascataSemAcionarELimitePendentesLiberamReservaUmaUnicaVez() throws Exception {
        for(String motivo:new String[]{"LIMITE_CASCATA","LIMITE_STOPS_PENDENTES"}) {
            Par par=preparar(); tornarStop(par,"STOP");
            String tipo=motivo.equals("LIMITE_CASCATA")?"ORDEM_ENCERRADA":"ORDEM_REJEITADA";
            String campos="\"motivo\":\""+motivo+"\""+(tipo.equals("ORDEM_ENCERRADA")?",\"executada\":0,\"cancelada\":10":"");
            var evento=eventoStop(par,tipo,campos,motivo.equals("LIMITE_CASCATA")?par.venda():par.compra());
            aplicar(evento); aplicar(evento); saldo("total","1000"); saldo("disponivel","1000"); saldo("reservado","0");
            assertEquals(Boolean.FALSE,QuarkusTransaction.requiringNew().call(()->em.createNativeQuery("SELECT stop_acionado FROM reserva WHERE intencao=:i").setParameter("i",par.compra()).getSingleResult()));
        }
    }
    @Test void causacaoECorrelacaoV2ExigemIdentidadeAtivoESessaoAntesDosEfeitos() throws Exception {
        Par par=preparar(); tornarStop(par,"STOP");
        var correto=eventoStop(par,"STOP_ACIONADO","\"tipoExecutavel\":\"MARKET\",\"sequencia\":3",par.venda());
        for(String campo:new String[]{"causacao","correlacao"}) {
            var e=correto.deepCopy(); e.put(campo,UUID.randomUUID().toString());
            assertThrows(RuntimeException.class,()->aplicar(e));
        }
        for(String update:new String[]{"ticker='BETA3'","data_sessao='2026-10-19'"}) {
            QuarkusTransaction.requiringNew().run(()->em.createNativeQuery("UPDATE reserva SET "+update+" WHERE intencao=:i").setParameter("i",par.venda()).executeUpdate());
            assertThrows(RuntimeException.class,()->aplicar(correto));
            QuarkusTransaction.requiringNew().run(()->em.createNativeQuery("UPDATE reserva SET ticker='ALFA3',data_sessao='2026-10-18' WHERE intencao=:i").setParameter("i",par.venda()).executeUpdate());
        }
        saldo("reservado","100"); assertEquals(0,valor("SELECT count(*) FROM matching_evento").intValueExact());
        aplicar(correto);
    }
    @Test void admissaoNovaStopEOutboxAtomicasEReentregaNaoPromoveLegado() throws Exception {
        preparar();
        UUID i=UUID.randomUUID();
        var perna=new br.prospero.mo.risco.dominio.Perna(i,br.prospero.mo.risco.dominio.TipoOrdem.STOP,2,null,new BigDecimal("10.00"),br.prospero.mo.risco.dominio.Validade.DIA);
        var c=new br.prospero.mo.risco.dominio.ComandoRisco(UUID.randomUUID(),i,"CONTA-002","ALFA3",br.prospero.mo.risco.dominio.Lado.COMPRA,new BigDecimal("10.00"),java.util.List.of(perna),null,java.time.LocalDate.of(2026,10,18));
        avaliarReal(c); avaliarReal(c);
        assertEquals(1,valor("SELECT count(*) FROM outbox WHERE id='"+EnvelopeAdmissao.id(i)+"'").intValueExact());
        Object[] metadata=QuarkusTransaction.requiringNew().call(()->(Object[])em.createNativeQuery("SELECT preco_gatilho,stop_acionado,preco_maximo,valor_restante FROM reserva WHERE intencao=:i").setParameter("i",i).getSingleResult());
        assertEquals(0,new BigDecimal("10.00").compareTo((BigDecimal)metadata[0])); assertEquals(Boolean.FALSE,metadata[1]);
        assertEquals(0,new BigDecimal("11.00").compareTo((BigDecimal)metadata[2]));
        assertEquals(0,new BigDecimal("22.00").compareTo((BigDecimal)metadata[3]));
        String payload=QuarkusTransaction.requiringNew().call(()->em.createNativeQuery("SELECT payload FROM outbox WHERE id=:i").setParameter("i",EnvelopeAdmissao.id(i)).getSingleResult().toString());
        assertEquals(2,JSON.readTree(payload).path("versao").asInt());
        UUID legado=UUID.randomUUID();
        var antigo=new br.prospero.mo.risco.dominio.ComandoRisco(UUID.randomUUID(),legado,c.conta(),c.ticker(),c.lado(),c.precoBase(),java.util.List.of(perna),null);
        avaliarReal(antigo);
        var repetido=new br.prospero.mo.risco.dominio.ComandoRisco(antigo.evento(),antigo.intencao(),antigo.conta(),antigo.ticker(),antigo.lado(),antigo.precoBase(),antigo.pernas(),null,c.dataSessao());
        avaliarReal(repetido);
        assertEquals(0,valor("SELECT count(*) FROM outbox WHERE id='"+EnvelopeAdmissao.id(legado)+"'").intValueExact());
        assertEquals(1,valor("SELECT count(*) FROM reserva WHERE intencao='"+legado+"' AND admissao IS NULL AND preco_gatilho IS NULL AND stop_acionado=false").intValueExact());
    }

    @Test void migracaoAditivaPreservaLegadoReservaEDeduplicacaoSemAdmitir() throws Exception {
        String proprio="upgrade_stops_"+UUID.randomUUID().toString().replace("-", "");
        UUID id=UUID.randomUUID();
        var configuracao=org.flywaydb.core.Flyway.configure().dataSource(System.getenv("MATCHING_TEST_DB_URL"),System.getenv("MATCHING_TEST_DB_USER"),System.getenv("MATCHING_TEST_DB_PASSWORD"))
                .schemas(proprio).defaultSchema(proprio).locations("classpath:db/migration");
        try {
            configuracao.target("3").load().migrate();
            try(var c=conexao();var s=c.createStatement()) {
                c.setSchema(proprio);
                s.execute("INSERT INTO decisao_risco(intencao,evento,conta,ticker,lado,resultado,valor_reservado,versao_parametros,decidida_em) VALUES('"+id+"','"+UUID.randomUUID()+"','CONTA-002','ALFA3','COMPRA','RESERVADA',20,1,now())");
                s.execute("INSERT INTO reserva(intencao,conta,ticker,recurso,valor,estado,criada_em,tipo,validade) VALUES('"+id+"','CONTA-002','ALFA3','CAIXA',20,'ATIVA',now(),'STOP','DIA')");
                s.execute("UPDATE conta_risco SET reservado=20,disponivel=total-20 WHERE conta='CONTA-002'");
                s.execute("INSERT INTO matching_evento(id,assinatura) VALUES('"+id+"','"+"a".repeat(64)+"')");

            }
            configuracao.target("latest").load().migrate();
            try(var c=conexao();var s=c.createStatement()) {
                c.setSchema(proprio);
                try(var r=s.executeQuery("SELECT valor,estado,admissao,preco_gatilho,stop_acionado FROM reserva WHERE intencao='"+id+"'")) {
                    assertTrue(r.next()); assertEquals(0,new BigDecimal("20").compareTo(r.getBigDecimal(1))); assertEquals("ATIVA",r.getString(2));
                    assertNull(r.getObject(3)); assertNull(r.getObject(4)); assertFalse(r.getBoolean(5));
                }
                try(var r=s.executeQuery("SELECT reservado,disponivel,total FROM conta_risco WHERE conta='CONTA-002'")) {
                    assertTrue(r.next()); assertEquals(0,new BigDecimal("20").compareTo(r.getBigDecimal(1)));
                    assertEquals(0,r.getBigDecimal(3).compareTo(r.getBigDecimal(1).add(r.getBigDecimal(2))));
                }
                try(var r=s.executeQuery("SELECT count(*) FROM outbox")){ assertTrue(r.next()); assertEquals(0,r.getInt(1)); }

                try(var r=s.executeQuery("SELECT count(*) FROM matching_evento WHERE id='"+id+"' AND assinatura='"+"a".repeat(64)+"'")){ assertTrue(r.next()); assertEquals(1,r.getInt(1)); }
            }
        } finally { try(var c=conexao();var s=c.createStatement()){ s.execute("DROP SCHEMA IF EXISTS "+proprio+" CASCADE"); } }
    }

    private void avaliarReal(br.prospero.mo.risco.dominio.ComandoRisco c) {
        // Os testes REST usam alternatives em memória; este IT conecta explicitamente os adaptadores reais.
        var actor=new br.prospero.mo.risco.actor.AvaliarRiscoActor(risco,outbox,java.time.Clock.systemUTC(),new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        QuarkusTransaction.requiringNew().run(()->actor.avaliar(c,null));
    }


    @Test
    void migracaoCriaCarteiraGrandeDoRoboFormador() throws Exception {
        // Schema próprio migrado do zero: os demais testes redefinem conta_risco de todas as contas.
        String proprio = "robo_formador_" + UUID.randomUUID().toString().replace("-", "");
        try {
            org.flywaydb.core.Flyway.configure().dataSource(System.getenv("MATCHING_TEST_DB_URL"),
                    System.getenv("MATCHING_TEST_DB_USER"), System.getenv("MATCHING_TEST_DB_PASSWORD"))
                    .schemas(proprio).defaultSchema(proprio).locations("classpath:db/migration").load().migrate();
            try (var c = conexao(); var s = c.createStatement()) {
                c.setSchema(proprio);
                try (var r = s.executeQuery("SELECT total, disponivel, reservado FROM conta_risco WHERE conta = 'CONTA-004'")) {
                    assertTrue(r.next());
                    assertEquals(0, new BigDecimal("100000000.00").compareTo(r.getBigDecimal(1)));
                    assertEquals(0, new BigDecimal("100000000.00").compareTo(r.getBigDecimal(2)));
                    assertEquals(0, BigDecimal.ZERO.compareTo(r.getBigDecimal(3)));
                }
                try (var r = s.executeQuery("SELECT count(*) FROM custodia_risco WHERE conta = 'CONTA-004' "
                        + "AND total = 100000 AND disponivel = 100000 AND reservado = 0")) {
                    assertTrue(r.next());
                    assertEquals(5, r.getInt(1));
                }
                try (var r = s.executeQuery("SELECT count(*) FROM conta_risco WHERE conta <> 'CONTA-004' AND total = 1000000.00")) {
                    assertTrue(r.next());
                    assertEquals(3, r.getInt(1), "contas existentes intactas");
                }
            }
        } finally {
            try (var c = conexao(); var s = c.createStatement()) { s.execute("DROP SCHEMA IF EXISTS " + proprio + " CASCADE"); }
        }
    }
}
