package br.prospero.mo.cancelamento;

import br.prospero.mo.cancelamento.actor.SolicitarCancelamentoActor;
import br.prospero.mo.mensageria.dominio.PromotorCancelamento;
import br.prospero.mo.mensageria.kafka.PublicadorOutbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.apache.kafka.clients.producer.MockProducer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RF10c2b: corrida solicitação × primeiro ACK nas duas ordens de chegada, em duas threads e duas transações reais.
 * A que chega depois bloqueia no lock do recibo (confirmado em pg_stat_activity) e deve ver o efeito da primeira:
 * exatamente um CANCELAR_ORDEM e nenhuma operação presa em AGUARDANDO_ACK.
 */
@QuarkusTest
@TestProfile(CancelamentoBanco.class)
class CancelamentoCorridaPostgresIT extends CancelamentoBase {
    @AfterAll
    static void removerSchemaProprio() throws Exception {
        CancelamentoBanco.removerSchemaProprio();
    }

    @Inject SolicitarCancelamentoActor actor;
    @Inject PromotorCancelamento promotor;

    @BeforeEach
    void zerar() {
        limpar();
    }

    private void publicar() {
        try (var p = new MockProducer<String, byte[]>(true, null, new org.apache.kafka.common.serialization.StringSerializer(),
                new org.apache.kafka.common.serialization.ByteArraySerializer())) {
            new PublicadorOutbox(em, p, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), promotor).publicarLote();
        }
    }

    /** Espera até 15 s por uma sessão do banco aguardando lock: prova de que a segunda transação está de fato concorrendo. */
    private static void aguardarSessaoBloqueada() throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try (var c = DriverManager.getConnection(System.getenv("MATCHING_TEST_DB_URL"), System.getenv("MATCHING_TEST_DB_USER"),
                System.getenv("MATCHING_TEST_DB_PASSWORD")); var s = c.createStatement()) {
            while (System.nanoTime() < limite) {
                try (var r = s.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND datname=current_database()")) {
                    r.next();
                    if (r.getInt(1) > 0) return;
                }
                Thread.sleep(25);
            }
        }
        fail("a segunda transação não ficou bloqueada no recibo");
    }

    private void afirmarEmitidoUmaVez(Alvo alvo) {
        assertEquals("COMANDO_EMITIDO::", estado(alvo));
        assertEquals(1, emitidos("CANCELAR_ORDEM", alvo));
        assertNotNull(escalar("SELECT confirmada_em FROM admissao_publicacao WHERE admissao=?1", alvo.admissao()));
        assertEquals(0, contar("cancelamento_operacao", "estado='AGUARDANDO_ACK'"));
    }

    @Test
    void solicitacaoChegaPrimeiroEOAckBloqueiaAteElaConfirmar() throws Exception {
        Alvo alvo = reservaComAdmissaoNaOutbox(false);
        var solicitou = new CountDownLatch(1);
        var ack = CompletableFuture.runAsync(() -> {
            try {
                assertTrue(solicitou.await(15, TimeUnit.SECONDS));
                QuarkusTransaction.requiringNew().run(this::publicar);
            } catch (InterruptedException erro) {
                throw new IllegalStateException(erro);
            }
        });
        QuarkusTransaction.requiringNew().run(() -> {
            actor.solicitar(alvo.pedido(), TRACE);
            solicitou.countDown();
            try {
                aguardarSessaoBloqueada();
            } catch (Exception erro) {
                throw new IllegalStateException(erro);
            }
        });
        ack.get(20, TimeUnit.SECONDS);
        afirmarEmitidoUmaVez(alvo);
    }

    @Test
    void primeiroAckChegaPrimeiroEASolicitacaoBloqueiaAteEleConfirmar() throws Exception {
        Alvo alvo = reservaComAdmissaoNaOutbox(true);
        var confirmou = new CountDownLatch(1);
        var solicitacao = CompletableFuture.runAsync(() -> {
            try {
                assertTrue(confirmou.await(15, TimeUnit.SECONDS));
                QuarkusTransaction.requiringNew().run(() -> actor.solicitar(alvo.pedido(), TRACE));
            } catch (InterruptedException erro) {
                throw new IllegalStateException(erro);
            }
        });
        QuarkusTransaction.requiringNew().run(() -> {
            publicar();
            confirmou.countDown();
            try {
                aguardarSessaoBloqueada();
            } catch (Exception erro) {
                throw new IllegalStateException(erro);
            }
        });
        solicitacao.get(20, TimeUnit.SECONDS);
        afirmarEmitidoUmaVez(alvo);
    }
}
