package br.prospero.fo.comum.kafka;

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
@TestProfile(AvisoOutboxPostgresIT.Banco.class)
class AvisoOutboxPostgresIT {
    private static final String SCHEMA = esquema();
    // Quarkus usa classloaders diferentes para perfil e teste; manter uma identidade por JVM.
    private static String esquema() {
        synchronized (System.getProperties()) {
            return System.getProperties().computeIfAbsent("qa.aviso.schema.front-office",
                    k -> "qa_aviso_" + UUID.randomUUID().toString().replace("-", "")).toString();
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

    void inserir(UUID id) {
        em.createNativeQuery("INSERT INTO outbox(id,topico,chave,tipo,payload,criado_em) VALUES(:id,'qa.topico','ALFA3','QA','{}',now())")
                .setParameter("id",id).executeUpdate();
    }
    long visiveis(UUID id) {
        try (var c = conexao(); var s = c.prepareStatement("SELECT count(*) FROM " + SCHEMA + ".outbox WHERE id=?")) {
            s.setObject(1,id); try (var r=s.executeQuery()) { r.next(); return r.getLong(1); }
        } catch (Exception erro) { throw new IllegalStateException(erro); }
    }

    @Test void soAcordaDepoisDoCommitQuandoLinhaJaEstaVisivel() {
        UUID id=UUID.randomUUID(); var chamadas=new AtomicInteger(); var vistas=new AtomicInteger();
        var aviso = new AvisoOutbox(registro, () -> { chamadas.incrementAndGet(); vistas.set((int)visiveis(id)); });
        QuarkusTransaction.requiringNew().run(() -> {
            inserir(id); aviso.registrar(); assertEquals(0,chamadas.get()); assertEquals(0,visiveis(id));
        });
        assertEquals(1,chamadas.get()); assertEquals(1,vistas.get()); assertEquals(1,visiveis(id));
    }

    @Test void multiplasInsercoesNaMesmaTransacaoProduzemUmSoAviso() {
        var chamadas=new AtomicInteger(); var aviso=new AvisoOutbox(registro,chamadas::incrementAndGet);
        QuarkusTransaction.requiringNew().run(() -> {
            for (int i=0;i<5;i++) { inserir(UUID.randomUUID()); aviso.registrar(); }
            assertEquals(0,chamadas.get());
        });
        assertEquals(1,chamadas.get());
        QuarkusTransaction.requiringNew().run(() -> { inserir(UUID.randomUUID()); aviso.registrar(); });
        assertEquals(2,chamadas.get(),"a marca não deve atravessar transações");
    }

    @Test void rollbackDescartaLinhaEAviso() {
        UUID id=UUID.randomUUID(); var chamadas=new AtomicInteger(); var aviso=new AvisoOutbox(registro,chamadas::incrementAndGet);
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            inserir(id); aviso.registrar(); throw new IllegalStateException("rollback intencional QA");
        }));
        assertEquals(0,chamadas.get()); assertEquals(0,visiveis(id));
    }

    @Test void falhaDoCallbackNaoReverteCommitNemPropagaErroFinanceiro() {
        UUID id=UUID.randomUUID(); var chamadas=new AtomicInteger();
        var aviso=new AvisoOutbox(registro,() -> { chamadas.incrementAndGet(); throw new java.util.concurrent.RejectedExecutionException("parada QA"); });
        assertDoesNotThrow(() -> QuarkusTransaction.requiringNew().run(() -> { inserir(id); aviso.registrar(); }));
        assertEquals(1,chamadas.get()); assertEquals(1,visiveis(id));
    }
    @Inject br.prospero.fo.ordens.jpa.RepositorioOrdensJpa ordens;
    @Test void gravadorRealDeOrdemSinalizaAposCommitENaoSinalizaRepeticao() {
        var chamadas=new AtomicInteger();
        io.quarkus.test.junit.QuarkusMock.installMockForType(new AvisoOutbox(registro,chamadas::incrementAndGet),AvisoOutbox.class);
        UUID id=UUID.randomUUID();var agora=java.time.Instant.now();String chave="qa-"+id;
        var ordem=new br.prospero.fo.ordens.dominio.Ordem(id,"CONTA-002","ALFA3",
                br.prospero.fo.ordens.dominio.LadoOrdem.COMPRA,br.prospero.fo.ordens.dominio.TipoOrdem.LIMIT,
                br.prospero.fo.ordens.dominio.Validade.DIA,10,new java.math.BigDecimal("10.00"),null,null,null,id,
                br.prospero.fo.ordens.dominio.EstadoOrdem.RECEBIDA,java.time.LocalDate.of(2026,10,26),agora,null,null);
        var msg=new br.prospero.fo.comum.dominio.MensagemSaida(UUID.randomUUID(),"qa.topico","ALFA3","QA","{}",null,agora);
        QuarkusTransaction.requiringNew().run(()-> {
            ordens.registrar("CONTA-002",chave,"a".repeat(64),agora,java.util.List.of(ordem),msg);assertEquals(0,chamadas.get());
        });
        assertEquals(1,chamadas.get());assertEquals(1,visiveis(msg.id()));
        QuarkusTransaction.requiringNew().run(()->ordens.registrar("CONTA-002",chave,"a".repeat(64),agora,java.util.List.of(ordem),msg));
        assertEquals(1,chamadas.get());
    }
}
