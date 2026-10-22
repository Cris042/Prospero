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
    @Inject br.prospero.fo.ordens.jpa.RepositorioOrdensJpa ordens;

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
    private UUID criarStop(String tipo) {
        UUID id=criar("CONTA-002","COMPRA");
        QuarkusTransaction.requiringNew().run(()->em.createNativeQuery("UPDATE ordem SET tipo=:t,validade='DIA',preco_gatilho=10,preco_limite=:p WHERE id=:i")
                .unwrap(org.hibernate.query.NativeQuery.class).setParameter("t",tipo)
                .setParameter("p",tipo.equals("STOP")?null:new BigDecimal("10.00"),BigDecimal.class).setParameter("i",id).executeUpdate());
        return id;
    }
    private UUID admissao(UUID intencao) {
        return UUID.nameUUIDFromBytes(("admissao:"+intencao).getBytes(StandardCharsets.UTF_8));
    }
    private EventoMatching eventoStop(UUID ordem,UUID raiz,String tipo,String campos) throws Exception {
        String s="""
                {"id":"%s","tipo":"%s","versao":2,"origem":"matching-engine","correlacao":"%s","causacao":"%s",
                "ocorridoEm":"2026-10-18T12:00:00Z","ticker":"ALFA3","dataSessao":"2026-10-18","traceparent":null,
                "dados":{"ordem":"%s","intencao":"%s","conta":"CONTA-002",%s}}
                """.formatted(UUID.randomUUID(),tipo,admissao(ordem),admissao(raiz),ordem,ordem,campos);
        return EnvelopeMatching.evento(s.getBytes(StandardCharsets.UTF_8),"ALFA3");
    }
    @Test void stopAtualizaEstadoPersistidoAntesDoRiscoEDeduplicaCascata() throws Exception {
        UUID compra=criarStop("STOP"),venda=criar("CONTA-003","VENDA");
        repositorio.aplicar(eventoStop(compra,compra,"ORDEM_ACEITA","\"sequencia\":1"));
        assertEquals("AGUARDANDO_GATILHO",estado(compra)[0]);
        var pendente=eventoStop(compra,compra,"STOP_PENDENTE","\"precoGatilho\":\"10.00\"");
        assertTrue(repositorio.aplicar(pendente)); assertFalse(repositorio.aplicar(pendente));
        repositorio.aplicar(eventoStop(compra,venda,"STOP_ACIONADO","\"tipoExecutavel\":\"MARKET\",\"sequencia\":3"));
        assertEquals("ABERTA",estado(compra)[0]);
        var original=negocio(compra,venda,"CONTA-003","201",UUID.randomUUID());
        var n=new EventoMatching(original.id(),original.correlacao(),original.ticker(),original.sessao(),original.assinatura(),original.detalhe(),admissao(venda));
        assertTrue(repositorio.aplicar(n)); assertFalse(repositorio.aplicar(n));
        assertEquals("PARCIALMENTE_EXECUTADA",estado(compra)[0]);
        var terminal=eventoStop(compra,venda,"ORDEM_ENCERRADA","\"motivo\":\"SALDO_CANCELADO_IOC\",\"executada\":4,\"cancelada\":6");
        assertTrue(repositorio.aplicar(terminal)); assertFalse(repositorio.aplicar(terminal));
        assertEquals("CANCELADA",estado(compra)[0]); assertEquals(4,((Number)estado(compra)[1]).longValue());
        repositorio.aplicar(eventoStop(compra,compra,"ORDEM_ACEITA","\"sequencia\":1"));
        assertEquals("CANCELADA",estado(compra)[0]);
        assertEquals(0,QuarkusTransaction.requiringNew().call(()->((BigDecimal)em.createNativeQuery("SELECT preco_gatilho FROM ordem WHERE id=:i").setParameter("i",compra).getSingleResult()).compareTo(new BigDecimal("10.00"))));
    }
    @Test void stopLimitPassivaAceitaV2EValidaCausacaoComMesmoAtivoESessao() throws Exception {
        UUID compra=criarStop("STOP_LIMIT"),venda=criar("CONTA-003","VENDA");
        var acionamento=eventoStop(compra,venda,"STOP_ACIONADO","\"tipoExecutavel\":\"LIMIT\",\"sequencia\":3");
        for(String update:new String[]{"ticker='BETA3'","data_sessao='2026-10-19'"}) {
            QuarkusTransaction.requiringNew().run(()->em.createNativeQuery("UPDATE ordem SET "+update+" WHERE id=:i").setParameter("i",venda).executeUpdate());
            assertThrows(MatchingIncoerenteException.class,()->repositorio.aplicar(acionamento));
            QuarkusTransaction.requiringNew().run(()->em.createNativeQuery("UPDATE ordem SET ticker='ALFA3',data_sessao='2026-10-18' WHERE id=:i").setParameter("i",venda).executeUpdate());
        }
        assertTrue(repositorio.aplicar(acionamento),"falhas de causacao nao gravam dedup");
        repositorio.aplicar(eventoStop(compra,venda,"OFERTA_NO_LIVRO","\"lado\":\"COMPRA\",\"preco\":\"10.00\",\"restante\":10"));
        var original=negocio(compra,venda,"CONTA-003","202",UUID.randomUUID());
        var detalhe=(EventoMatching.Negocio)original.detalhe();
        var passiva=new EventoMatching.Negocio(detalhe.id(),detalhe.preco(),detalhe.quantidade(),detalhe.compra(),detalhe.venda(),LadoOrdem.VENDA);
        var e=new EventoMatching(original.id(),admissao(venda),original.ticker(),original.sessao(),"hash-passiva",passiva,admissao(venda));
        assertTrue(repositorio.aplicar(e)); assertEquals("PARCIALMENTE_EXECUTADA",estado(compra)[0]);
    }

    @Test void migracaoAditivaPreservaLegadoReservaEDeduplicacaoSemAdmitir() throws Exception {
        String proprio="upgrade_stops_"+UUID.randomUUID().toString().replace("-", "");
        UUID id=UUID.randomUUID();
        var configuracao=org.flywaydb.core.Flyway.configure().dataSource(System.getenv("MATCHING_TEST_DB_URL"),System.getenv("MATCHING_TEST_DB_USER"),System.getenv("MATCHING_TEST_DB_PASSWORD"))
                .schemas(proprio).defaultSchema(proprio).locations("classpath:db/migration");
        try {
            configuracao.target("6").load().migrate();
            try(var c=conexao();var s=c.createStatement()) {
                c.setSchema(proprio);
                s.execute("INSERT INTO ordem(id,conta,ticker,lado,tipo,validade,quantidade,preco_gatilho,grupo_envio,estado,data_sessao,recebida_em) VALUES('"+id+"','CONTA-002','ALFA3','COMPRA','STOP','DIA',2,10,'"+id+"','RESERVADA','2026-10-18',now())");
                s.execute("INSERT INTO matching_evento(id,assinatura) VALUES('"+id+"','"+"a".repeat(64)+"')");

            }
            configuracao.target("latest").load().migrate();
            try(var c=conexao();var s=c.createStatement()) {
                c.setSchema(proprio);
                try(var r=s.executeQuery("SELECT estado,preco_gatilho,admissao_matching,quantidade_executada,quantidade_cancelada FROM ordem WHERE id='"+id+"'")) {
                    assertTrue(r.next()); assertEquals("RESERVADA",r.getString(1)); assertEquals(0,new BigDecimal("10").compareTo(r.getBigDecimal(2)));
                    assertNull(r.getObject(3)); assertEquals(0,r.getLong(4)); assertEquals(0,r.getLong(5));
                }
                assertEquals(1,s.executeUpdate("UPDATE ordem SET estado='AGUARDANDO_GATILHO' WHERE id='"+id+"'"));

                try(var r=s.executeQuery("SELECT count(*) FROM matching_evento WHERE id='"+id+"' AND assinatura='"+"a".repeat(64)+"'")){ assertTrue(r.next()); assertEquals(1,r.getInt(1)); }
            }
        } finally { try(var c=conexao();var s=c.createStatement()){ s.execute("DROP SCHEMA IF EXISTS "+proprio+" CASCADE"); } }
    }

    @Test void registroRealStopPersisteAdmissaoEEstadoNaoRegridePorRiscoTardio() throws Exception {
        UUID id=UUID.randomUUID(); var agora=java.time.Instant.parse("2026-10-18T12:00:00Z");
        var ordem=new br.prospero.fo.ordens.dominio.Ordem(id,"CONTA-002","ALFA3",LadoOrdem.COMPRA,
                br.prospero.fo.ordens.dominio.TipoOrdem.STOP,br.prospero.fo.ordens.dominio.Validade.DIA,2,null,new BigDecimal("10.00"),null,null,id,
                br.prospero.fo.ordens.dominio.EstadoOrdem.RECEBIDA,LocalDate.of(2026,10,18),agora,null);
        var mensagem=new br.prospero.fo.comum.dominio.MensagemSaida(UUID.randomUUID(),"risco.comandos.v1","CONTA-002","AVALIAR_RISCO","{}",null,agora);
        ordens.registrar("CONTA-002","it-stop-"+id,"b".repeat(64),agora,java.util.List.of(ordem),mensagem);
        var registro=QuarkusTransaction.requiringNew().call(()->em.createNativeQuery("SELECT admissao_matching FROM ordem WHERE id=:i").setParameter("i",id).getSingleResult());
        assertEquals(admissao(id),registro,"caminho real persiste metadado que fixtures manuais nao comprovariam");
        repositorio.aplicar(eventoStop(id,id,"ORDEM_ACEITA","\"sequencia\":1"));
        assertEquals("AGUARDANDO_GATILHO",estado(id)[0]);
        var resultado=new br.prospero.fo.ordens.dominio.ResultadoRisco(UUID.randomUUID(),id,"CONTA-002",
                br.prospero.fo.ordens.dominio.EstadoOrdem.RESERVADA,null);
        assertEquals(0,ordens.aplicarRisco(resultado)); assertEquals("AGUARDANDO_GATILHO",estado(id)[0]);
    }


    private EventoMatching eventoOco(UUID ordem,UUID grupo,String tipo,String campos) {
        String s="""
                {"id":"%s","tipo":"%s","versao":3,"origem":"matching-engine","correlacao":"%s","causacao":"%s",
                "ocorridoEm":"2026-10-18T12:00:00Z","ticker":"ALFA3","dataSessao":"2026-10-18","traceparent":null,
                "dados":{"ordem":"%s","intencao":"%s","conta":"CONTA-003",%s}}
                """.formatted(UUID.randomUUID(),tipo,admissao(grupo),admissao(grupo),ordem,grupo,campos);
        return EnvelopeMatching.evento(s.getBytes(StandardCharsets.UTF_8),"ALFA3");
    }
    @Test void registroRealOcoGravaAdmissaoNasDuasPernasEProjetaReducaoECancelamento() throws Exception {
        UUID grupo=UUID.randomUUID(), tp=UUID.randomUUID(), sl=UUID.randomUUID(); var agora=java.time.Instant.parse("2026-10-18T12:00:00Z");
        var dia=LocalDate.of(2026,10,18);
        var pernas=java.util.List.of(
                new br.prospero.fo.ordens.dominio.Ordem(tp,"CONTA-003","ALFA3",LadoOrdem.VENDA,br.prospero.fo.ordens.dominio.TipoOrdem.LIMIT,
                        br.prospero.fo.ordens.dominio.Validade.DIA,10,new BigDecimal("12.00"),null,grupo,br.prospero.fo.ordens.dominio.PapelOco.TAKE_PROFIT,grupo,
                        br.prospero.fo.ordens.dominio.EstadoOrdem.RECEBIDA,dia,agora,null),
                new br.prospero.fo.ordens.dominio.Ordem(sl,"CONTA-003","ALFA3",LadoOrdem.VENDA,br.prospero.fo.ordens.dominio.TipoOrdem.STOP,
                        br.prospero.fo.ordens.dominio.Validade.DIA,10,null,new BigDecimal("9.00"),grupo,br.prospero.fo.ordens.dominio.PapelOco.STOP_LOSS,grupo,
                        br.prospero.fo.ordens.dominio.EstadoOrdem.RECEBIDA,dia,agora,null));
        var mensagem=new br.prospero.fo.comum.dominio.MensagemSaida(UUID.randomUUID(),"risco.comandos.v1","CONTA-003","AVALIAR_RISCO","{}",null,agora);
        ordens.registrar("CONTA-003","it-oco-"+grupo,"c".repeat(64),agora,pernas,mensagem);
        assertEquals(2L,((Number)QuarkusTransaction.requiringNew().call(()->em.createNativeQuery(
                "SELECT count(*) FROM ordem WHERE admissao_matching=:a").setParameter("a",admissao(grupo)).getSingleResult())).longValue());
        repositorio.aplicar(eventoOco(tp,grupo,"ORDEM_ACEITA","\"sequencia\":1"));
        repositorio.aplicar(eventoOco(sl,grupo,"ORDEM_ACEITA","\"sequencia\":2"));
        assertEquals("ABERTA",estado(tp)[0]); assertEquals("AGUARDANDO_GATILHO",estado(sl)[0]);
        var reducao=eventoOco(sl,grupo,"ORDEM_REDUZIDA","\"reduzida\":4,\"restante\":6");
        assertTrue(repositorio.aplicar(reducao)); assertFalse(repositorio.aplicar(reducao));
        assertEquals(4L,((Number)QuarkusTransaction.requiringNew().call(()->em.createNativeQuery(
                "SELECT quantidade_reduzida FROM ordem WHERE id=:i").setParameter("i",sl).getSingleResult())).longValue());
        repositorio.aplicar(eventoOco(sl,grupo,"ORDEM_ENCERRADA","\"motivo\":\"CANCELADA_OCO\",\"executada\":0,\"cancelada\":6"));
        assertEquals("CANCELADA",estado(sl)[0]);
    }

    @Test
    void migracaoCriaContaDoRoboFormador() {
        assertEquals("Robô formador de mercado", QuarkusTransaction.requiringNew().call(() ->
                em.createNativeQuery("SELECT nome FROM conta WHERE id = 'CONTA-004'").getSingleResult()));
    }
}
