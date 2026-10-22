package br.prospero.mo.matching;

import br.prospero.mo.mensageria.dominio.MensagemSaida;
import br.prospero.mo.sessao.jpa.RepositorioSessaoJpa;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** RF05c.4b: o mesmo encerramento grava uma linha só na outbox, inclusive com pedidos concorrentes. Opt-in. */
@QuarkusTest
@TestProfile(MatchingPostgresIT.Banco.class)
class EncerramentoSessaoPostgresIT {
    @AfterAll
    static void removerSchemaProprio() throws Exception {
        MatchingPostgresIT.removerSchemaProprio();
    }

    @Inject RepositorioSessaoJpa repositorio;
    @Inject EntityManager em;

    @Test
    void registroConcorrenteDoMesmoComandoGravaUmaVez() throws Exception {
        UUID id = UUID.randomUUID();
        var comando = new MensagemSaida(id, "matching.comandos.v1", "GAMA3", "ENCERRAR_SESSAO", "{}", null, Instant.now());
        try (var executor = Executors.newFixedThreadPool(4)) {
            java.util.List<Future<Boolean>> resultados = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                resultados.add(executor.submit(() -> QuarkusTransaction.requiringNew().call(() -> repositorio.registrar(comando))));
            }
            long novos = 0;
            for (var r : resultados) if (r.get()) novos++;
            assertEquals(1, novos);
        }
        assertEquals(1L, ((Number) QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(
                "SELECT count(*) FROM outbox WHERE id=:i").setParameter("i", id).getSingleResult())).longValue());
    }
}
