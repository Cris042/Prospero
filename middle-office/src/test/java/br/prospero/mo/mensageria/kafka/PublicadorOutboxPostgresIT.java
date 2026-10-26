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
@TestProfile(PublicadorOutboxPostgresIT.Banco.class)
class PublicadorOutboxPostgresIT {
    private static final String SCHEMA = esquema();
    // Quarkus usa classloaders diferentes para perfil e teste; manter uma identidade por JVM.
    private static String esquema() {
        synchronized (System.getProperties()) {
            return System.getProperties().computeIfAbsent("qa.publicador.schema.middle-office",
                    k -> "qa_publicador_" + UUID.randomUUID().toString().replace("-", "")).toString();
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

    @org.junit.jupiter.api.BeforeEach void limparOutboxPropria() {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("DELETE FROM outbox").executeUpdate());
    }
    void inserir(UUID id, int ordem) {
        em.createNativeQuery("INSERT INTO outbox(id,topico,chave,tipo,payload,traceparent,criado_em) VALUES(:id,'qa.topico','ALFA3','QA',:p,:t,:c)")
                .setParameter("id",id).setParameter("p",id.toString())
                .setParameter("t","00-11111111111111111111111111111111-2222222222222222-01")
                .setParameter("c",java.time.OffsetDateTime.parse("2026-10-26T10:00:00Z").plusSeconds(ordem)).executeUpdate();
    }
    int estado(UUID id, boolean publicado) {
        try (var c=conexao();var s=c.prepareStatement("SELECT tentativas,publicado_em IS NOT NULL FROM " + SCHEMA + ".outbox WHERE id=?")) {
            s.setObject(1,id);try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(publicado,r.getBoolean(2));return r.getInt(1);}
        } catch(Exception erro){throw new IllegalStateException(erro);}
    }
    org.apache.kafka.clients.producer.MockProducer<String,byte[]> produtor(boolean automatico) {
        return new org.apache.kafka.clients.producer.MockProducer<>(automatico, null,
                new org.apache.kafka.common.serialization.StringSerializer(),new org.apache.kafka.common.serialization.ByteArraySerializer());
    }
    PublicadorOutbox publicador(org.apache.kafka.clients.producer.Producer<String,byte[]> produtor) {
        return new PublicadorOutbox(em,produtor,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test void confirmacaoKafkaVemAntesDaMarcacaoTransacional() throws Exception {
        UUID id=UUID.randomUUID();QuarkusTransaction.requiringNew().run(()->inserir(id,0));
        var enviado=new java.util.concurrent.CountDownLatch(1);
        var produtor=new org.apache.kafka.clients.producer.MockProducer<String,byte[]>(false, null,
                new org.apache.kafka.common.serialization.StringSerializer(),new org.apache.kafka.common.serialization.ByteArraySerializer()) {
            @Override public synchronized java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata> send(
                    org.apache.kafka.clients.producer.ProducerRecord<String,byte[]> registro) {
                var futuro=super.send(registro);enviado.countDown();return futuro;
            }
        };
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var pub=publicador(produtor);
            var futuro=executor.submit(()->QuarkusTransaction.requiringNew().call(pub::publicarLote));
            assertTrue(enviado.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,estado(id,false));assertFalse(futuro.isDone());
            assertTrue(produtor.completeNext());
            assertEquals(AgendadorOutbox.Resultado.ESGOTADO,futuro.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,estado(id,true));
            var registro=produtor.history().getFirst();assertEquals("qa.topico",registro.topic());assertEquals("ALFA3",registro.key());
            assertArrayEquals("00-11111111111111111111111111111111-2222222222222222-01".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    registro.headers().lastHeader("traceparent").value());
        } finally {produtor.close();}
    }

    @Test void ackSeguidoDeRollbackPermiteReentregaDoMesmoPayload() {
        UUID id=UUID.randomUUID();QuarkusTransaction.requiringNew().run(()->inserir(id,0));
        try(var produtor=produtor(true)) {
            var pub=publicador(produtor);
            assertThrows(RuntimeException.class,()->QuarkusTransaction.requiringNew().run(()-> {
                assertEquals(AgendadorOutbox.Resultado.ESGOTADO,pub.publicarLote());
                throw new IllegalStateException("rollback depois do ack QA");
            }));
            assertEquals(1,produtor.history().size());assertEquals(0,estado(id,false));
            assertEquals(AgendadorOutbox.Resultado.ESGOTADO,QuarkusTransaction.requiringNew().call(pub::publicarLote));
            assertEquals(2,produtor.history().size());assertArrayEquals(produtor.history().get(0).value(),produtor.history().get(1).value());
            assertEquals(0,estado(id,true));
        }
    }

    @Test void falhaNaPrimeiraMensagemInterrompeLoteEPreservaOrdemNoRetry() {
        UUID primeira=UUID.randomUUID(),segunda=UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(()->{inserir(primeira,0);inserir(segunda,1);});
        var falhar=new java.util.concurrent.atomic.AtomicBoolean(true);var chamadas=new AtomicInteger();
        try(var produtor=new org.apache.kafka.clients.producer.MockProducer<String,byte[]>(true, null,
                new org.apache.kafka.common.serialization.StringSerializer(),new org.apache.kafka.common.serialization.ByteArraySerializer()) {
            @Override public synchronized java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata> send(
                    org.apache.kafka.clients.producer.ProducerRecord<String,byte[]> registro) {
                chamadas.incrementAndGet();if(falhar.get())throw new IllegalStateException("Kafka indisponível QA");return super.send(registro);
            }
        }) {
            var pub=publicador(produtor);
            assertEquals(AgendadorOutbox.Resultado.FALHOU,QuarkusTransaction.requiringNew().call(pub::publicarLote));
            assertEquals(1,chamadas.get());assertEquals(1,estado(primeira,false));assertEquals(0,estado(segunda,false));
            falhar.set(false);
            assertEquals(AgendadorOutbox.Resultado.ESGOTADO,QuarkusTransaction.requiringNew().call(pub::publicarLote));
            assertEquals(java.util.List.of(primeira.toString(),segunda.toString()),produtor.history().stream()
                    .map(r->new String(r.value(),java.nio.charset.StandardCharsets.UTF_8)).toList());
            estado(primeira,true);estado(segunda,true);
        }
    }

    @Test void loteDeCinquentaContinuaDrenagemOrdenadaSemAlterarBytes() {
        var ids=new java.util.ArrayList<UUID>();for(int i=0;i<51;i++)ids.add(UUID.randomUUID());
        QuarkusTransaction.requiringNew().run(()->{for(int i=0;i<ids.size();i++)inserir(ids.get(i),i);});
        try(var produtor=produtor(true)) {
            var pub=publicador(produtor);
            assertEquals(AgendadorOutbox.Resultado.CONTINUAR,QuarkusTransaction.requiringNew().call(pub::publicarLote));
            assertEquals(50,produtor.history().size());estado(ids.get(50),false);
            assertEquals(AgendadorOutbox.Resultado.ESGOTADO,QuarkusTransaction.requiringNew().call(pub::publicarLote));
            assertEquals(ids.stream().map(UUID::toString).toList(),produtor.history().stream()
                    .map(r->new String(r.value(),java.nio.charset.StandardCharsets.UTF_8)).toList());
            for(var id:ids)estado(id,true);
        }
    }
    @Test void falhaParcialMarcaSomenteMensagensConfirmadasAntesDaFalha() {
        UUID primeira=UUID.randomUUID(),segunda=UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(()->{inserir(primeira,0);inserir(segunda,1);});
        var chamadas=new AtomicInteger();
        try(var produtor=new org.apache.kafka.clients.producer.MockProducer<String,byte[]>(true,null,
                new org.apache.kafka.common.serialization.StringSerializer(),new org.apache.kafka.common.serialization.ByteArraySerializer()) {
            @Override public synchronized java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata> send(
                    org.apache.kafka.clients.producer.ProducerRecord<String,byte[]> registro) {
                if(chamadas.incrementAndGet()==2)throw new IllegalStateException("segunda mensagem falhou QA");
                return super.send(registro);
            }
        }) {
            var pub=publicador(produtor);
            assertEquals(AgendadorOutbox.Resultado.FALHOU,QuarkusTransaction.requiringNew().call(pub::publicarLote));
            assertEquals(0,estado(primeira,true));assertEquals(1,estado(segunda,false));
            assertEquals(1,produtor.history().size());
            assertEquals(AgendadorOutbox.Resultado.ESGOTADO,QuarkusTransaction.requiringNew().call(pub::publicarLote));
            assertEquals(2,produtor.history().size());estado(segunda,true);
        }
    }
}
