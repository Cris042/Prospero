package br.prospero.mo.mensageria.kafka;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Banco e schema descartáveis: prova JTA/visibilidade real, sem broker ou stack financeira. */
@QuarkusTest
@TestProfile(PublicacaoAdmissaoPostgresIT.Banco.class)
class PublicacaoAdmissaoPostgresIT {
    private static final String SCHEMA = esquema();
    // Quarkus usa classloaders diferentes para perfil e teste; manter uma identidade por JVM.
    private static String esquema() {
        synchronized (System.getProperties()) {
            return System.getProperties().computeIfAbsent("qa.admissao.schema.middle-office",
                    k -> "qa_admissao_" + UUID.randomUUID().toString().replace("-", "")).toString();
        }
    }
    public static class Banco implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() {
            try (var c = conexao(); var s = c.createStatement()) { s.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA); }
            catch (Exception erro) { throw new IllegalStateException("Banco descartável obrigatório", erro); }
            return Map.of("quarkus.datasource.active", "true", "quarkus.hibernate-orm.active", "true",
                    "quarkus.datasource.jdbc.url", System.getenv("MATCHING_TEST_DB_URL") + "?currentSchema=" + SCHEMA,
                    "quarkus.datasource.username", System.getenv("MATCHING_TEST_DB_USER"),
                    "quarkus.datasource.password", System.getenv("MATCHING_TEST_DB_PASSWORD"),
                    "quarkus.flyway.migrate-at-start", "true", "quarkus.flyway.schemas", SCHEMA);
        }
    }
    static java.sql.Connection conexao() throws Exception {
        return DriverManager.getConnection(System.getenv("MATCHING_TEST_DB_URL"),
                System.getenv("MATCHING_TEST_DB_USER"), System.getenv("MATCHING_TEST_DB_PASSWORD"));
    }
    @AfterAll static void removerSchema() throws Exception {
        try (var c = conexao(); var s = c.createStatement()) { s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE"); }
    }
    @Inject TransactionSynchronizationRegistry registro;
    @Inject EntityManager em;

    @Test void schemaPossuiReciboDuravelDeAdmissao() {
        Object tabela = QuarkusTransaction.requiringNew().call(() ->
                em.createNativeQuery("SELECT CAST(to_regclass('admissao_publicacao') AS text)").getSingleResult());
        assertNotNull(tabela, "RF10c1 exige tabela durável independente da outbox");
    }
    private static final String TOPICO = "matching.comandos.v1";
    private static final String TIPO = "ORDEM_ADMITIDA";
    private static final String TRACE = "00-11111111111111111111111111111111-2222222222222222-01";
    @Inject br.prospero.mo.mensageria.jpa.OutboxJpa outbox;

    private UUID reserva() {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO decisao_risco(intencao,evento,conta,ticker,lado,resultado,valor_reservado,versao_parametros,decidida_em) VALUES(:id,:id,'CONTA-001','ALFA3','COMPRA','RESERVADA',10,1,now())")
                .setParameter("id", id).executeUpdate();
        em.createNativeQuery("INSERT INTO reserva(intencao,conta,ticker,recurso,valor,estado,criada_em,admissao) VALUES(:id,'CONTA-001','ALFA3','CAIXA',10,'ATIVA',now(),:id)")
                .setParameter("id", id).executeUpdate();
        return id;
    }
    private br.prospero.mo.mensageria.dominio.MensagemSaida mensagem(UUID id) {
        return new br.prospero.mo.mensageria.dominio.MensagemSaida(id, TOPICO, "ALFA3", TIPO,
                "{\"texto\":\"ação\", \"id\":\"" + id + "\"}", TRACE, java.time.Instant.parse("2026-10-27T10:00:00Z"));
    }
    private br.prospero.mo.mensageria.jpa.PublicacoesAdmissaoJpa recibos() {
        return new br.prospero.mo.mensageria.jpa.PublicacoesAdmissaoJpa(em);
    }
    private br.prospero.mo.mensageria.jpa.PublicacoesAdmissaoJpa.Confirmada snapshot(br.prospero.mo.mensageria.dominio.MensagemSaida m) {
        return new br.prospero.mo.mensageria.jpa.PublicacoesAdmissaoJpa.Confirmada(m.id(), m.topico(), m.chave(), m.tipo(), m.payload(), m.traceparent());
    }
    private long contar(String tabela, UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery("SELECT count(*) FROM " + tabela + " WHERE " + (tabela.equals("reserva") ? "admissao" : tabela.equals("admissao_publicacao") ? "admissao" : "id") + "=:id").setParameter("id", id).getSingleResult()).longValue());
    }
    private Object confirmado(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery("SELECT confirmada_em FROM admissao_publicacao WHERE admissao=:id").setParameter("id", id).getSingleResult());
    }
    private boolean publicada(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> (Boolean) em.createNativeQuery("SELECT publicado_em IS NOT NULL FROM outbox WHERE id=:id").setParameter("id", id).getSingleResult());
    }
    private void semearRecibo(br.prospero.mo.mensageria.dominio.MensagemSaida m) {
        em.createNativeQuery("INSERT INTO admissao_publicacao(admissao,topico,chave,tipo,payload,traceparent,criada_em) VALUES(:id,:t,:c,:tipo,:p,:h,now()) ON CONFLICT DO NOTHING")
                .setParameter("id", m.id()).setParameter("t", m.topico()).setParameter("c", m.chave()).setParameter("tipo",m.tipo()).setParameter("p",m.payload()).setParameter("h",m.traceparent()).executeUpdate();
    }
    private br.prospero.mo.mensageria.dominio.MensagemSaida prepararPublicacao() {
        return QuarkusTransaction.requiringNew().call(() -> {
            var m = mensagem(reserva()); outbox.gravar(m); semearRecibo(m); return m;
        });
    }
    private org.apache.kafka.clients.producer.MockProducer<String,byte[]> produtor(boolean automatico) {
        return new org.apache.kafka.clients.producer.MockProducer<>(automatico,null,
                new org.apache.kafka.common.serialization.StringSerializer(),new org.apache.kafka.common.serialization.ByteArraySerializer());
    }
    private PublicadorOutbox publicador(org.apache.kafka.clients.producer.Producer<String,byte[]> p) {
        return new PublicadorOutbox(em,p,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }
    @org.junit.jupiter.api.BeforeEach void limparOutboxTemporaria() {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("DELETE FROM outbox").executeUpdate());
    }

    @Test void gravadorRealCriaReciboPendenteNaMesmaTransacaoDaReservaEOutbox() {
        var m = QuarkusTransaction.requiringNew().call(() -> {var msg=mensagem(reserva());outbox.gravar(msg);return msg;});
        assertEquals(1,contar("admissao_publicacao",m.id()));
        assertNull(confirmado(m.id())); assertFalse(publicada(m.id()));
        Object[] dados=QuarkusTransaction.requiringNew().call(() -> (Object[])em.createNativeQuery("SELECT payload,traceparent,chave,topico,tipo FROM admissao_publicacao WHERE admissao=:id").setParameter("id",m.id()).getSingleResult());
        assertArrayEquals(new Object[]{m.payload(),TRACE,"ALFA3",TOPICO,TIPO},dados);
    }

    @Test void rollbackDoGravadorDesfazReservaOutboxERecibo() {
        UUID[] id={null};
        assertThrows(RuntimeException.class,()->QuarkusTransaction.requiringNew().run(()->{id[0]=reserva();outbox.gravar(mensagem(id[0]));throw new IllegalStateException("rollback QA");}));
        assertEquals(0,contar("reserva",id[0]));assertEquals(0,contar("outbox",id[0]));assertEquals(0,contar("admissao_publicacao",id[0]));
    }

    @Test void ackConfirmaOutboxEReciboSomenteDepoisDaRespostaDoProdutor() throws Exception {
        var m=prepararPublicacao();var enviado=new java.util.concurrent.CountDownLatch(1);
        try(var p=new org.apache.kafka.clients.producer.MockProducer<String,byte[]>(false,null,new org.apache.kafka.common.serialization.StringSerializer(),new org.apache.kafka.common.serialization.ByteArraySerializer()) {
            @Override public synchronized java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata> send(org.apache.kafka.clients.producer.ProducerRecord<String,byte[]> r) {var f=super.send(r);enviado.countDown();return f;}
        };var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var pub=publicador(p);var f=executor.submit(()->QuarkusTransaction.requiringNew().call(pub::publicarLote));
            assertTrue(enviado.await(5,java.util.concurrent.TimeUnit.SECONDS));assertFalse(publicada(m.id()));assertNull(confirmado(m.id()));assertFalse(f.isDone());
            assertTrue(p.completeNext());f.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(publicada(m.id()));assertNotNull(confirmado(m.id()));
            assertArrayEquals(m.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8),p.history().getFirst().value());
            assertArrayEquals(TRACE.getBytes(java.nio.charset.StandardCharsets.UTF_8),p.history().getFirst().headers().lastHeader("traceparent").value());
        }
    }

    @Test void ackComRollbackReentregaBytesEHeaderSemConfirmacaoAntecipada() {
        var m=prepararPublicacao();
        try(var p=produtor(true)) {
            var pub=publicador(p);
            assertThrows(RuntimeException.class,()->QuarkusTransaction.requiringNew().run(()->{pub.publicarLote();throw new IllegalStateException("queda após ACK QA");}));
            assertFalse(publicada(m.id()));assertNull(confirmado(m.id()));
            QuarkusTransaction.requiringNew().run(pub::publicarLote);
            assertTrue(publicada(m.id()));assertNotNull(confirmado(m.id()));assertEquals(2,p.history().size());
            assertArrayEquals(p.history().get(0).value(),p.history().get(1).value());
            assertEquals(p.history().get(0).key(),p.history().get(1).key());
            assertArrayEquals(p.history().get(0).headers().lastHeader("traceparent").value(),p.history().get(1).headers().lastHeader("traceparent").value());
        }
    }

    @Test void primeiraConfirmacaoPersisteAoRepublicarEExpurgarOutbox() {
        var m=prepararPublicacao();
        try(var p=produtor(true)) {
            var pub=publicador(p);QuarkusTransaction.requiringNew().run(pub::publicarLote);Object primeira=confirmado(m.id());assertNotNull(primeira);
            QuarkusTransaction.requiringNew().run(()->em.createNativeQuery("UPDATE outbox SET publicado_em=NULL WHERE id=:id").setParameter("id",m.id()).executeUpdate());
            QuarkusTransaction.requiringNew().run(pub::publicarLote);assertEquals(primeira,confirmado(m.id()));
            QuarkusTransaction.requiringNew().run(()->{em.createNativeQuery("UPDATE outbox SET publicado_em=now()-interval '2 days' WHERE id=:id").setParameter("id",m.id()).executeUpdate();em.createNativeQuery("DELETE FROM outbox WHERE publicado_em < now()-interval '1 day'").executeUpdate();});
            assertEquals(0,contar("outbox",m.id()));assertEquals(1,contar("admissao_publicacao",m.id()));assertEquals(primeira,confirmado(m.id()));
        }
    }

    @Test void faltaDeReciboAposAckAbortaMarcacaoDaOutbox() {
        var m=prepararPublicacao();QuarkusTransaction.requiringNew().run(()->em.createNativeQuery("DELETE FROM admissao_publicacao WHERE admissao=:id").setParameter("id",m.id()).executeUpdate());
        try(var p=produtor(true)) {var pub=publicador(p);assertThrows(RuntimeException.class,()->QuarkusTransaction.requiringNew().run(pub::publicarLote));assertFalse(publicada(m.id()));assertEquals(1,p.history().size());}
    }

    @Test void divergenciaDeCadaCampoDoSnapshotAbortaTxInteira() {
        for(String campo:java.util.List.of("id","topico","chave","tipo","payload","traceparent")) {
            var m=prepararPublicacao();var original=snapshot(m);
            var adulterado=new br.prospero.mo.mensageria.jpa.PublicacoesAdmissaoJpa.Confirmada(campo.equals("id")?UUID.randomUUID():original.id(),campo.equals("topico")?"outro":original.topico(),campo.equals("chave")?"BETA3":original.chave(),campo.equals("tipo")?"OUTRO":original.tipo(),campo.equals("payload")?"{ }":original.payload(),campo.equals("traceparent")?null:original.traceparent());
            assertThrows(RuntimeException.class,()->QuarkusTransaction.requiringNew().run(()->{em.createNativeQuery("UPDATE outbox SET publicado_em=now() WHERE id=:id").setParameter("id",m.id()).executeUpdate();recibos().confirmar(java.util.List.of(adulterado));}),campo);
            assertFalse(publicada(m.id()),campo);assertNull(confirmado(m.id()),campo);
        }
    }

    @Test void divergenciaDeReciboOuOutboxAbortaMarcacaoMesmoComSnapshotValido() {
        for(String tabela:java.util.List.of("admissao_publicacao","outbox"))for(String campo:java.util.List.of("chave","payload","traceparent")) {
            var m=prepararPublicacao();
            QuarkusTransaction.requiringNew().run(()->em.createNativeQuery("UPDATE "+tabela+" SET "+campo+"=:valor WHERE "+(tabela.equals("outbox")?"id":"admissao")+"=:id").setParameter("valor",campo.equals("chave")?"BETA3":campo.equals("payload")?"{ }":null).setParameter("id",m.id()).executeUpdate());
            assertThrows(RuntimeException.class,()->QuarkusTransaction.requiringNew().run(()->{em.createNativeQuery("UPDATE outbox SET publicado_em=now() WHERE id=:id").setParameter("id",m.id()).executeUpdate();recibos().confirmar(java.util.List.of(snapshot(m)));}),tabela+campo);
            assertFalse(publicada(m.id()));assertNull(confirmado(m.id()));
        }
    }

    @Test void gravadorRecusaAdmissaoSemReservaETopicoIncompativel() {
        var semReserva=mensagem(UUID.randomUUID());
        assertThrows(RuntimeException.class,()->QuarkusTransaction.requiringNew().run(()->outbox.gravar(semReserva)));
        assertEquals(0,contar("outbox",semReserva.id()));
        var id=QuarkusTransaction.requiringNew().call(this::reserva);
        var m=mensagem(id);var invalida=new br.prospero.mo.mensageria.dominio.MensagemSaida(id,"qa.errado",m.chave(),TIPO,m.payload(),TRACE,m.criadaEm());
        assertThrows(RuntimeException.class,()->QuarkusTransaction.requiringNew().run(()->outbox.gravar(invalida)));
        assertEquals(0,contar("outbox",id));assertEquals(0,contar("admissao_publicacao",id));
    }

    @Test void outrosTiposContinuamPublicandoSemRecibo() {
        UUID id=UUID.randomUUID();var m=new br.prospero.mo.mensageria.dominio.MensagemSaida(id,"risco.resultados.v1","CONTA-001","RESULTADO_RISCO","{}",null,java.time.Instant.now());
        QuarkusTransaction.requiringNew().run(()->outbox.gravar(m));assertEquals(0,contar("admissao_publicacao",id));
        try(var p=produtor(true)) {QuarkusTransaction.requiringNew().run(publicador(p)::publicarLote);assertTrue(publicada(id));}
    }

    @Inject br.prospero.mo.risco.jpa.RepositorioRiscoJpa riscoJpa;
    private br.prospero.mo.risco.actor.AvaliarRiscoActor actorRiscoReal() {
        return new br.prospero.mo.risco.actor.AvaliarRiscoActor(riscoJpa,outbox,java.time.Clock.systemUTC(),new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }
    private br.prospero.mo.risco.dominio.ComandoRisco comandoRisco() {
        return new br.prospero.mo.risco.dominio.ComandoRisco(UUID.randomUUID(),UUID.randomUUID(),"CONTA-001","ALFA3",
                br.prospero.mo.risco.dominio.Lado.COMPRA,new java.math.BigDecimal("10.00"),java.util.List.of(
                new br.prospero.mo.risco.dominio.Perna(UUID.randomUUID(),br.prospero.mo.risco.dominio.TipoOrdem.LIMIT,1,new java.math.BigDecimal("10.00"),null,br.prospero.mo.risco.dominio.Validade.DIA)),TRACE,java.time.LocalDate.of(2026,10,27));
    }
    @Test void actorRiscoRealConfirmaReservaAdmissaoEReciboUmaVez() {
        var c=comandoRisco();var a=QuarkusTransaction.requiringNew().call(()->actorRiscoReal().avaliar(c,TRACE));
        assertEquals(br.prospero.mo.risco.dominio.ResultadoRisco.RESERVADA,a.decisao().resultado());
        UUID id=br.prospero.mo.risco.helper.EnvelopeAdmissao.id(c.intencao());
        assertEquals(1,contar("reserva",id));assertEquals(1,contar("outbox",id));assertEquals(1,contar("admissao_publicacao",id));assertNull(confirmado(id));
        assertTrue(QuarkusTransaction.requiringNew().call(()->actorRiscoReal().avaliar(c,TRACE)).repetida());assertEquals(1,contar("admissao_publicacao",id));
    }
    @Test void actorRiscoRealEmRollbackDesfazCaixaReservaOutboxERecibo() {
        var c=comandoRisco();UUID id=br.prospero.mo.risco.helper.EnvelopeAdmissao.id(c.intencao());
        Object antes=QuarkusTransaction.requiringNew().call(()->em.createNativeQuery("SELECT reservado FROM conta_risco WHERE conta='CONTA-001'").getSingleResult());
        assertThrows(RuntimeException.class,()->QuarkusTransaction.requiringNew().run(()->{actorRiscoReal().avaliar(c,TRACE);throw new IllegalStateException("rollback risco QA");}));
        assertEquals(0,contar("reserva",id));assertEquals(0,contar("outbox",id));assertEquals(0,contar("admissao_publicacao",id));
        assertEquals(antes,QuarkusTransaction.requiringNew().call(()->em.createNativeQuery("SELECT reservado FROM conta_risco WHERE conta='CONTA-001'").getSingleResult()));
    }
    @Test void falhaDoProdutorPreservaAmbasMarcacoesPendentes() {
        var m=prepararPublicacao();
        try(var p=new org.apache.kafka.clients.producer.MockProducer<String,byte[]>(true,null,new org.apache.kafka.common.serialization.StringSerializer(),new org.apache.kafka.common.serialization.ByteArraySerializer()) {
            @Override public synchronized java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata> send(org.apache.kafka.clients.producer.ProducerRecord<String,byte[]> r) {throw new IllegalStateException("sem ACK QA");}
        }) {
            assertEquals(AgendadorOutbox.Resultado.FALHOU,QuarkusTransaction.requiringNew().call(publicador(p)::publicarLote));
            assertFalse(publicada(m.id()));assertNull(confirmado(m.id()));
        }
    }
}
