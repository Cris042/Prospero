package br.prospero.mo.matching;

import br.prospero.mo.mensageria.dominio.MensagemSaida;
import br.prospero.mo.sessao.helper.EnvelopeEncerramento;
import br.prospero.mo.sessao.jpa.RepositorioSessaoJpa;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** QA RF05c.4b: dois POST iguais concorrentes gravam um único comando na outbox PostgreSQL (ON CONFLICT). Opt-in. */
@QuarkusTest
@TestProfile(MatchingPostgresIT.Banco.class)
class EncerramentoSessaoQaPostgresIT {
    @AfterAll
    static void removerSchemaProprio() throws Exception {
        MatchingPostgresIT.removerSchemaProprio();
    }

    @Inject RepositorioSessaoJpa repositorio;
    @Inject EntityManager em;

    private static MensagemSaida comando(String ticker, LocalDate sessao, Instant agora) {
        return new MensagemSaida(EnvelopeEncerramento.id(ticker, sessao), EnvelopeEncerramento.TOPICO, ticker,
                EnvelopeEncerramento.TIPO, EnvelopeEncerramento.serializar(ticker, sessao, agora, null), null, agora);
    }

    @Test
    void repeticaoConcorrenteGravaUmaLinhaSo() throws Exception {
        LocalDate sessao = LocalDate.parse("2026-10-18");
        UUID id = EnvelopeEncerramento.id("DELTA3", sessao);
        int n = 8;
        var largada = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Boolean>> resultados = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Instant agora = Instant.parse("2026-10-18T21:00:00Z").plusMillis(i);
                resultados.add(pool.submit(() -> QuarkusTransaction.requiringNew().call(() -> {
                    largada.await(10, TimeUnit.SECONDS);
                    boolean novo = repositorio.registrar(comando("DELTA3", sessao, agora));
                    Thread.sleep(50); // mantém a transação aberta para as concorrentes esperarem no índice único
                    return novo;
                })));
            }
            long novos = 0;
            for (var r : resultados) if (r.get(30, TimeUnit.SECONDS)) novos++;
            assertEquals(1, novos, "um único registro novo; os demais são repetição");
        } finally {
            pool.shutdownNow();
        }
        long linhas = QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT count(*) FROM outbox WHERE id = :id").setParameter("id", id).getSingleResult()).longValue());
        assertEquals(1, linhas);
        String tipo = QuarkusTransaction.requiringNew().call(() -> (String) em.createNativeQuery(
                "SELECT tipo || '|' || topico || '|' || chave FROM outbox WHERE id = :id").setParameter("id", id).getSingleResult());
        assertEquals("ENCERRAR_SESSAO|matching.comandos.v1|DELTA3", tipo);
        assertTrue(QuarkusTransaction.requiringNew().call(() -> repositorio.registrar(comando("DELTA3", LocalDate.parse("2026-10-19"),
                Instant.now()))), "outra data é outro comando");
    }

    @Test
    void registrarExigeTransacaoDoChamador() {
        assertThrows(Exception.class, () -> repositorio.registrar(comando("OMEGA3", LocalDate.parse("2026-10-18"), Instant.now())));
    }
}
