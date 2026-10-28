package br.prospero.fo.ordens;

import br.prospero.fo.comum.dominio.MensagemSaida;
import br.prospero.fo.ordens.dominio.EstadoCancelamento;
import br.prospero.fo.ordens.dominio.EstadoOrdem;
import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.dominio.MotivoRejeicao;
import br.prospero.fo.ordens.dominio.OperacaoCancelamento;
import br.prospero.fo.ordens.dominio.ResultadoCancelamento;
import br.prospero.fo.ordens.dominio.ResultadoRisco;
import br.prospero.fo.ordens.helper.EnvelopeRisco;
import br.prospero.fo.ordens.jpa.RepositorioCancelamentoJpa;
import br.prospero.fo.ordens.jpa.RepositorioOrdensJpa;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RF10 no FO com PostgreSQL real: V12, unicidade por ordem, outbox atômica e transições que só avançam. Opt-in. */
@QuarkusTest
@TestProfile(MatchingPostgresIT.Banco.class)
class CancelamentoOperacaoPostgresIT {
    @AfterAll
    static void removerSchemaProprio() throws Exception {
        MatchingPostgresIT.removerSchemaProprio();
    }

    @Inject RepositorioCancelamentoJpa cancelamentos;
    @Inject RepositorioOrdensJpa ordens;
    @Inject EntityManager em;

    @BeforeEach
    void limpar() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (String tabela : new String[]{"cancelamento_operacao", "trajetoria_marco", "trajetoria_identidade", "outbox"}) {
                em.createNativeQuery("DELETE FROM " + tabela).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM ordem WHERE id::text LIKE 'c0000000-0000-4000-8000-%'").executeUpdate();
        });
    }

    private static UUID id(String final4) {
        return UUID.fromString("c0000000-0000-4000-8000-00000000" + final4);
    }

    private UUID ordem(String final4, String conta, String estado, UUID grupoOco) {
        UUID id = id(final4);
        UUID admissao = UUID.nameUUIDFromBytes(("admissao:" + id).getBytes(StandardCharsets.UTF_8));
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
                INSERT INTO ordem(id,conta,ticker,lado,tipo,validade,quantidade,preco_limite,grupo_envio,grupo_oco,papel_oco,
                estado,data_sessao,recebida_em,admissao_matching)
                VALUES(:i,:c,'ALFA3','VENDA','LIMIT','DIA',10,10,:i,:g,:p,:e,'2026-10-18',now(),:a)
                """).unwrap(org.hibernate.query.NativeQuery.class).setParameter("i", id).setParameter("c", conta)
                .setParameter("g", grupoOco, UUID.class).setParameter("p", grupoOco == null ? null : "TAKE_PROFIT", String.class)
                .setParameter("e", estado).setParameter("a", admissao).executeUpdate());
        return id;
    }

    private OperacaoCancelamento registrar(UUID ordem, String conta, EstadoCancelamento estado, boolean comComando) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var o = cancelamentos.travarOrdem(conta, ordem).orElseThrow();
            Instant agora = Instant.now();
            MensagemSaida comando = comComando ? new MensagemSaida(EnvelopeRisco.idCancelamentoSolicitado(ordem),
                    EnvelopeRisco.TOPICO_COMANDOS, conta, EnvelopeRisco.TIPO_CANCELAMENTO_SOLICITADO,
                    EnvelopeRisco.cancelamentoSolicitado(o, agora, null), null, agora) : null;
            return cancelamentos.registrar(o, estado, comando, agora);
        });
    }

    private long contar(String sql, UUID ordem) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(sql)
                .setParameter("o", ordem).getSingleResult()).longValue());
    }

    private long outbox(UUID ordem) {
        return contar("SELECT count(*) FROM outbox WHERE tipo='CANCELAMENTO_SOLICITADO' AND payload LIKE '%\"ordem\":\"'"
                + "||CAST(:o AS text)||'\"%'", ordem);
    }

    private long marcos(UUID ordem, String tipo) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT count(*) FROM trajetoria_marco WHERE ordem=:o AND tipo=:t").setParameter("o", ordem)
                .setParameter("t", tipo).getSingleResult()).longValue());
    }

    private ResultadoRisco risco(UUID ordem, String conta, EstadoOrdem estado) {
        return new ResultadoRisco(UUID.randomUUID(), ordem, conta, estado,
                estado == EstadoOrdem.REJEITADA ? MotivoRejeicao.SALDO_INSUFICIENTE : null);
    }

    private ResultadoCancelamento resultado(UUID ordem, String conta, boolean confirmado, String motivo) {
        return new ResultadoCancelamento(UUID.randomUUID(), ordem, conta, confirmado, motivo, Instant.now(), null,
                "assinatura-" + UUID.randomUUID());
    }

    @Test
    void unicaOperacaoPorOrdemComOutboxNaMesmaTransacao() {
        UUID o = ordem("0001", "CONTA-002", "ABERTA", null);
        var op = registrar(o, "CONTA-002", EstadoCancelamento.SOLICITADO, true);
        assertEquals(EstadoCancelamento.SOLICITADO, op.estado());
        assertEquals(1, outbox(o));
        assertEquals(1, marcos(o, "CANCELAMENTO_SOLICITADO"));
        assertThrows(RuntimeException.class, () -> registrar(o, "CONTA-002", EstadoCancelamento.SOLICITADO, true));
        assertEquals(1, outbox(o), "a falha desfaz a outbox da tentativa");
        assertEquals(op.ordem(), cancelamentos.buscar("CONTA-002", o).orElseThrow().ordem());
        assertTrue(cancelamentos.buscar("CONTA-003", o).isEmpty(), "isolamento por conta");
    }

    @Test
    void aguardandoRiscoPromoveUmaVezQuandoReservadaESemOutboxAntes() {
        UUID o = ordem("0002", "CONTA-002", "RECEBIDA", null);
        registrar(o, "CONTA-002", EstadoCancelamento.AGUARDANDO_RISCO, false);
        assertEquals(0, outbox(o));
        assertEquals(1, QuarkusTransaction.requiringNew().call(() -> ordens.aplicarRisco(risco(o, "CONTA-002", EstadoOrdem.RESERVADA))));
        assertEquals(EstadoCancelamento.SOLICITADO, cancelamentos.buscar("CONTA-002", o).orElseThrow().estado());
        assertEquals(1, outbox(o));
        assertEquals(1, marcos(o, "CANCELAMENTO_SOLICITADO"));
        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> ordens.aplicarRisco(risco(o, "CONTA-002", EstadoOrdem.RESERVADA))),
                "reentrega do risco não emite de novo");
        assertEquals(1, outbox(o));
    }

    @Test
    void riscoRejeitadoRecusaSemEmitir() {
        UUID o = ordem("0003", "CONTA-002", "RECEBIDA", null);
        registrar(o, "CONTA-002", EstadoCancelamento.AGUARDANDO_RISCO, false);
        QuarkusTransaction.requiringNew().run(() -> ordens.aplicarRisco(risco(o, "CONTA-002", EstadoOrdem.REJEITADA)));
        var op = cancelamentos.buscar("CONTA-002", o).orElseThrow();
        assertEquals(EstadoCancelamento.RECUSADO, op.estado());
        assertEquals("RISCO_REJEITADO", op.motivo());
        assertEquals(0, outbox(o));
        assertEquals(1, marcos(o, "CANCELAMENTO_RECUSADO"));
    }

    @Test
    void decisaoDoMatchingEResultadoDoMoSoAvancamEIgnoramRepeticaoERegressao() {
        UUID o = ordem("0004", "CONTA-002", "ABERTA", null);
        registrar(o, "CONTA-002", EstadoCancelamento.SOLICITADO, true);
        assertTrue(QuarkusTransaction.requiringNew().call(() ->
                cancelamentos.aplicarDecisao(o, "CONTA-002", EventoMatching.ResultadoCancelamento.APLICADO)));
        var decidida = cancelamentos.buscar("CONTA-002", o).orElseThrow();
        assertEquals(EstadoCancelamento.DECIDIDO, decidida.estado());
        assertEquals(EventoMatching.ResultadoCancelamento.APLICADO, decidida.decisao());
        var confirmado = resultado(o, "CONTA-002", true, null);
        assertTrue(cancelamentos.aplicarResultado(confirmado));
        assertFalse(cancelamentos.aplicarResultado(confirmado), "repetição");
        assertFalse(cancelamentos.aplicarResultado(resultado(o, "CONTA-002", false, "SEM_SALDO_VIVO")), "estado final não regride");
        assertEquals(EstadoCancelamento.CONFIRMADO, cancelamentos.buscar("CONTA-002", o).orElseThrow().estado());
        assertEquals(1, marcos(o, "CANCELAMENTO_CONFIRMADO"));
        assertEquals(0, marcos(o, "CANCELAMENTO_RECUSADO"));
    }

    @Test
    void resultadoDoMoPodeChegarAntesDaDecisaoDoMatchingSemRegredirADecisao() {
        UUID o = ordem("0005", "CONTA-002", "ABERTA", null);
        registrar(o, "CONTA-002", EstadoCancelamento.SOLICITADO, true);
        assertTrue(cancelamentos.aplicarResultado(resultado(o, "CONTA-002", true, null)));
        QuarkusTransaction.requiringNew().call(() ->
                cancelamentos.aplicarDecisao(o, "CONTA-002", EventoMatching.ResultadoCancelamento.APLICADO));
        var op = cancelamentos.buscar("CONTA-002", o).orElseThrow();
        assertEquals(EstadoCancelamento.CONFIRMADO, op.estado());
        assertEquals(EventoMatching.ResultadoCancelamento.APLICADO, op.decisao());
    }

    @Test
    void resultadoOuDecisaoSemOperacaoOuDeOutraContaNaoFazemNada() {
        UUID o = ordem("0006", "CONTA-002", "ABERTA", null);
        assertFalse(cancelamentos.aplicarResultado(resultado(o, "CONTA-002", true, null)));
        assertFalse(QuarkusTransaction.requiringNew().call(() ->
                cancelamentos.aplicarDecisao(o, "CONTA-002", EventoMatching.ResultadoCancelamento.APLICADO)));
        registrar(o, "CONTA-002", EstadoCancelamento.SOLICITADO, true);
        assertFalse(cancelamentos.aplicarResultado(resultado(o, "CONTA-003", true, null)));
        assertEquals(EstadoCancelamento.SOLICITADO, cancelamentos.buscar("CONTA-002", o).orElseThrow().estado());
    }

    @Test
    void recusaDoMoGuardaOMotivoEOCheckImpedeMotivoForaDaRecusa() {
        UUID o = ordem("0007", "CONTA-002", "ABERTA", null);
        registrar(o, "CONTA-002", EstadoCancelamento.SOLICITADO, true);
        assertTrue(cancelamentos.aplicarResultado(resultado(o, "CONTA-002", false, "ADMISSAO_NAO_COMPROVADA")));
        var op = cancelamentos.buscar("CONTA-002", o).orElseThrow();
        assertEquals("ADMISSAO_NAO_COMPROVADA", op.motivo());
        UUID p = ordem("0008", "CONTA-002", "ABERTA", null);
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
                INSERT INTO cancelamento_operacao(ordem,conta,estado,motivo,solicitada_em,atualizada_em)
                VALUES(:o,'CONTA-002','SOLICITADO','ORIGEM_OCO',now(),now())""").setParameter("o", p).executeUpdate()));
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
                INSERT INTO cancelamento_operacao(ordem,conta,estado,motivo,solicitada_em,atualizada_em)
                VALUES(:o,'CONTA-002','RECUSADO','MOTIVO_INVENTADO',now(),now())""").setParameter("o", p).executeUpdate()));
    }

    @Test
    void buscaEmLoteEPendenciaDoGauge() {
        UUID a = ordem("0009", "CONTA-002", "ABERTA", null), b = ordem("000a", "CONTA-002", "ABERTA", null);
        assertEquals(0.0, cancelamentos.pendenteSegundosMax());
        registrar(a, "CONTA-002", EstadoCancelamento.SOLICITADO, true);
        registrar(b, "CONTA-002", EstadoCancelamento.SOLICITADO, true);
        assertEquals(2, cancelamentos.buscar("CONTA-002", List.of(a, b, UUID.randomUUID())).size());
        assertTrue(cancelamentos.buscar("CONTA-002", List.of()).isEmpty());
        assertTrue(cancelamentos.pendenteSegundosMax() >= 0);
        cancelamentos.aplicarResultado(resultado(a, "CONTA-002", true, null));
        cancelamentos.aplicarResultado(resultado(b, "CONTA-002", false, "SEM_SALDO_VIVO"));
        assertEquals(0.0, cancelamentos.pendenteSegundosMax(), "só operações sem resultado final contam");
    }
}
