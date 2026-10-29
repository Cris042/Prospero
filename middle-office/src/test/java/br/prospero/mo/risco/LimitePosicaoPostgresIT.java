package br.prospero.mo.risco;

import br.prospero.mo.cancelamento.CancelamentoBanco;
import br.prospero.mo.risco.actor.AvaliarRiscoActor;
import br.prospero.mo.risco.dominio.ComandoRisco;
import br.prospero.mo.risco.dominio.Lado;
import br.prospero.mo.risco.dominio.MotivoRejeicao;
import br.prospero.mo.risco.dominio.Perna;
import br.prospero.mo.risco.dominio.ResultadoRisco;
import br.prospero.mo.risco.dominio.TipoOrdem;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-020 em PostgreSQL real: duas compras concorrentes não passam juntas do limite de posição. Opt-in. */
@QuarkusTest
@TestProfile(CancelamentoBanco.class)
class LimitePosicaoPostgresIT {
    @AfterAll
    static void removerSchemaProprio() throws Exception {
        CancelamentoBanco.removerSchemaProprio();
    }

    @Inject AvaliarRiscoActor actor;
    @Inject EntityManager em;

    private static ComandoRisco compra(long quantidade) {
        return new ComandoRisco(UUID.randomUUID(), UUID.randomUUID(), "CONTA-001", "BETA3", Lado.COMPRA,
                new BigDecimal("20.00"), List.of(new Perna(UUID.randomUUID(), TipoOrdem.LIMIT, quantidade,
                new BigDecimal("20.00"), null)), null);
    }

    private long compraAberta() {
        return ((Number) QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(
                "SELECT compra_aberta FROM custodia_risco WHERE conta='CONTA-001' AND ticker='BETA3'")
                .getSingleResult())).longValue();
    }

    @Test
    void comprasConcorrentesQueJuntasPassariamOLimiteAdmitemSoUma() throws Exception {
        // CONTA-001 tem 1000 BETA3: limite 1150 deixa espaço para uma compra de 100, nunca para duas.
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE custodia_risco SET limite_posicao = 1150 WHERE conta='CONTA-001' AND ticker='BETA3'").executeUpdate());
        var largada = new CountDownLatch(1);
        List<Future<br.prospero.mo.risco.dominio.Decisao>> resultados = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                var comando = compra(100);
                resultados.add(executor.submit(() -> {
                    largada.await();
                    return actor.avaliar(comando, null).decisao();
                }));
            }
            largada.countDown();
            List<br.prospero.mo.risco.dominio.Decisao> decisoes = new ArrayList<>();
            for (var r : resultados) decisoes.add(r.get());
            assertEquals(1, decisoes.stream().filter(d -> d.resultado() == ResultadoRisco.RESERVADA).count());
            assertEquals(1, decisoes.stream().filter(d -> d.motivo() == MotivoRejeicao.LIMITE_POSICAO_EXCEDIDO).count());
        }
        assertEquals(100, compraAberta());

        // Rejeição por saldo depois de passar o limite devolve a posição na mesma transação.
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE conta_risco SET total = reservado, disponivel = 0 WHERE conta='CONTA-001'").executeUpdate());
        var semCaixa = actor.avaliar(compra(10), null).decisao();
        assertEquals(MotivoRejeicao.SALDO_INSUFICIENTE, semCaixa.motivo());
        assertEquals(100, compraAberta());
    }

    @Test
    void padraoDoAtivoECinquentaMilEExcecaoDoRoboEDuzentosMil() {
        Object[] linha = (Object[]) QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery("""
                SELECT a.limite_posicao, r.limite_posicao, c.limite_posicao FROM ativo_risco a
                JOIN custodia_risco r ON r.ticker = a.ticker AND r.conta = 'CONTA-004'
                JOIN custodia_risco c ON c.ticker = a.ticker AND c.conta = 'CONTA-002' WHERE a.ticker = 'ALFA3'""")
                .getSingleResult());
        assertEquals(50_000L, ((Number) linha[0]).longValue());
        assertEquals(200_000L, ((Number) linha[1]).longValue());
        assertNull(linha[2], "contas comuns usam o padrão do ativo");
    }
}
