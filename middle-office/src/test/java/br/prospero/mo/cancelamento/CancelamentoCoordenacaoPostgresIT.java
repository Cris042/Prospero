package br.prospero.mo.cancelamento;

import br.prospero.mo.cancelamento.actor.SolicitarCancelamentoActor;
import br.prospero.mo.mensageria.dominio.MensagemInvalidaException;
import br.prospero.mo.mensageria.dominio.ParticaoBloqueadaException;
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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RF10c2b: coordenação do cancelamento no MO sobre PostgreSQL 16 real, JTA real e MockProducer. Opt-in como os demais ITs. */
@QuarkusTest
@TestProfile(CancelamentoBanco.class)
class CancelamentoCoordenacaoPostgresIT extends CancelamentoBase {
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

    private void solicitar(Alvo alvo) {
        actor.solicitar(alvo.pedido(), TRACE);
    }

    private PublicadorOutbox publicador(MockProducer<String, byte[]> produtor) {
        return new PublicadorOutbox(em, produtor, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), promotor);
    }

    private MockProducer<String, byte[]> produtor() {
        return new MockProducer<>(true, null, new org.apache.kafka.common.serialization.StringSerializer(),
                new org.apache.kafka.common.serialization.ByteArraySerializer());
    }

    // 2. ACK já gravado: um único CANCELAR_ORDEM e repetição sem nova outbox.
    @Test
    void solicitacaoComAckGravadoEmiteUmCancelarOrdemERepeticaoNaoMudaNada() {
        Alvo alvo = reserva(false, 10, Recibo.CONFIRMADO);
        String saldo = saldo(alvo);
        solicitar(alvo);
        assertEquals("COMANDO_EMITIDO::", estado(alvo));
        assertEquals(1, emitidos("CANCELAR_ORDEM", alvo));
        Object[] saida = (Object[]) QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(
                "SELECT id, topico, chave, traceparent, payload FROM outbox WHERE tipo='CANCELAR_ORDEM'").getSingleResult());
        assertEquals(br.prospero.mo.cancelamento.helper.EnvelopeCancelamento.comando(alvo.ordem()), saida[0]);
        assertEquals("matching.comandos.v1", saida[1]);
        assertEquals("ALFA3", saida[2]);
        assertEquals(TRACE, saida[3]);
        Object assinatura = escalar("SELECT assinatura FROM cancelamento_operacao WHERE ordem=?1", alvo.ordem());
        actor.solicitar(alvo.pedido(), "00-11111111111111111111111111111111-2222222222222222-01");
        assertEquals(1, emitidos("CANCELAR_ORDEM", alvo));
        assertEquals(assinatura, escalar("SELECT assinatura FROM cancelamento_operacao WHERE ordem=?1", alvo.ordem()));
        assertEquals(1, contar("cancelamento_operacao", "ordem=?1", alvo.ordem()));
        assertEquals(saldo, saldo(alvo));
        var outraAssinatura = new br.prospero.mo.cancelamento.dominio.SolicitacaoCancelamento(alvo.pedido().id(), alvo.ordem(),
                alvo.intencao(), alvo.conta(), "BETA3", SESSAO);
        var erro = assertThrows(ParticaoBloqueadaException.class, () -> actor.solicitar(outraAssinatura, TRACE));
        assertEquals("CANCELAMENTO_INCONSISTENTE", erro.getMessage());
    }

    // 3. Sem ACK a operação espera e nada é emitido.
    @Test
    void solicitacaoSemAckFicaAguardandoSemCancelarOrdem() {
        Alvo alvo = reserva(true, 10, Recibo.PENDENTE);
        String saldo = saldo(alvo);
        solicitar(alvo);
        assertEquals("AGUARDANDO_ACK::", estado(alvo));
        assertEquals(0, emitidos("CANCELAR_ORDEM", alvo));
        assertEquals(0, contar("outbox", "tipo='RESULTADO_CANCELAMENTO'"));
        assertEquals(saldo, saldo(alvo));
    }

    // 3/12. Nem o passar do tempo promove ou libera: só o ACK do broker.
    @Test
    void esperaLongaSemAckNaoPromoveNemLiberaSaldo() {
        Alvo alvo = reserva(false, 10, Recibo.PENDENTE);
        solicitar(alvo);
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("UPDATE cancelamento_operacao SET criada_em=now()-interval '30 days'").executeUpdate());
        try (var p = produtor()) {
            QuarkusTransaction.requiringNew().run(publicador(p)::publicarLote);
        }
        solicitar(alvo);
        assertEquals("AGUARDANDO_ACK::", estado(alvo));
        assertEquals(0, emitidos("CANCELAR_ORDEM", alvo));
        assertEquals("100/90/10", saldo(alvo));
    }

    // 4/5. Promoção no ACK real, na mesma transação da marcação; rollback desfaz a promoção.
    @Test
    void primeiroAckPromoveEmitindoUmCancelarOrdemERollbackDesfazAPromocao() {
        Alvo alvo = reservaComAdmissaoNaOutbox(false);
        solicitar(alvo);
        assertEquals("AGUARDANDO_ACK::", estado(alvo));
        try (var p = produtor()) {
            var pub = publicador(p);
            assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
                pub.publicarLote();
                throw new IllegalStateException("queda após o ACK");
            }));
            assertEquals("AGUARDANDO_ACK::", estado(alvo));
            assertEquals(0, emitidos("CANCELAR_ORDEM", alvo));
            assertNull(escalar("SELECT confirmada_em FROM admissao_publicacao WHERE admissao=?1", alvo.admissao()));
            QuarkusTransaction.requiringNew().run(pub::publicarLote);
            assertEquals("COMANDO_EMITIDO::", estado(alvo));
            assertEquals(1, emitidos("CANCELAR_ORDEM", alvo));
            QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("UPDATE outbox SET publicado_em=NULL WHERE tipo='ORDEM_ADMITIDA'").executeUpdate());
            QuarkusTransaction.requiringNew().run(pub::publicarLote);
            assertEquals(1, emitidos("CANCELAR_ORDEM", alvo), "segundo ACK da admissão não reemite");
            assertTrue(p.history().stream().anyMatch(r -> new String(r.value(), java.nio.charset.StandardCharsets.UTF_8).contains("\"CANCELAR_ORDEM\"")));
        }
    }

    // 6. Cada recusa local grava o RESULTADO_CANCELAMENTO correto e preserva o saldo.
    @Test
    void recusasLocaisGravamResultadoComMotivoCorretoSemTocarSaldo() {
        Alvo rejeitada = rejeitada(), legada = legada();
        Alvo oco = ocoComRecibo();
        Alvo base = reserva(false, 10, Recibo.CONFIRMADO);
        Alvo divergente = new Alvo(UUID.randomUUID(), base.intencao(), base.conta(), false, 10);
        Alvo encerrada = reserva(true, 10, Recibo.CONFIRMADO);
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("UPDATE reserva SET estado='ENCERRADA',quantidade_restante=0,valor_restante=0 WHERE intencao=:i")
                .setParameter("i", encerrada.intencao()).executeUpdate());
        String saldoBase = saldo(base);
        var esperado = java.util.Map.of(rejeitada, "RISCO_REJEITADO", legada, "ADMISSAO_NAO_COMPROVADA", oco, "ORIGEM_OCO",
                divergente, "ALVO_DIVERGENTE", encerrada, "SEM_SALDO_VIVO");
        for (var caso : esperado.entrySet()) {
            Alvo alvo = caso.getKey();
            solicitar(alvo);
            solicitar(alvo);
            assertEquals("RECUSADO:" + caso.getValue() + ":", estado(alvo), caso.getValue());
            assertEquals(1, emitidos("RESULTADO_CANCELAMENTO", alvo), caso.getValue());
            assertEquals(0, emitidos("CANCELAR_ORDEM", alvo), caso.getValue());
            String payload = (String) escalar("SELECT payload FROM outbox WHERE tipo='RESULTADO_CANCELAMENTO' AND payload LIKE ?1", "%" + alvo.ordem() + "%");
            assertTrue(payload.contains("\"resultado\":\"RECUSADO\",\"motivo\":\"" + caso.getValue() + "\""), payload);
        }
        assertEquals(saldoBase, saldo(base));
        assertEquals(5, contar("outbox", "tipo='RESULTADO_CANCELAMENTO' AND topico='risco.resultados.v1'"));
    }

    // 7. Decisão de risco ausente bloqueia a partição e não grava nada.
    @Test
    void decisaoDeRiscoAusenteBloqueiaSemGravarNada() {
        Alvo alvo = new Alvo(UUID.randomUUID(), UUID.randomUUID(), "CONTA-001", false, 10);
        var erro = assertThrows(ParticaoBloqueadaException.class, () -> solicitar(alvo));
        assertEquals("CANCELAMENTO_SEM_DECISAO_DE_RISCO", erro.getMessage());
        assertEquals(0, contar("cancelamento_operacao", "TRUE"));
        assertEquals(0, contar("outbox", "TRUE"));
    }

    // 8. Terminal CANCELADA_MANUAL libera a reserva uma vez: caixa, custódia, parcial e Stop pendente.
    @Test
    void terminalLiberaAReservaUmaVezEmTodosOsCenarios() {
        record Cenario(String nome, boolean compra, long restante, boolean stop, String esperado) { }
        for (var c : List.of(new Cenario("caixa", true, 10, false, "1000.00/1000.00/0.00"), new Cenario("custodia", false, 10, false, "100/100/0"),
                new Cenario("caixa parcial", true, 6, false, "960.00/960.00/0.00"), new Cenario("custodia parcial", false, 6, false, "96/96/0"),
                new Cenario("stop pendente", false, 10, true, "100/100/0"))) {
            limpar();
            Alvo alvo = reserva(c.compra(), c.restante(), Recibo.CONFIRMADO);
            if (c.stop()) {
                QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("UPDATE reserva SET tipo='STOP',preco_limite=NULL,preco_gatilho=10.50 WHERE intencao=:i")
                        .setParameter("i", alvo.intencao()).executeUpdate());
            }
            solicitar(alvo);
            byte[] terminal = terminal(alvo, 10 - c.restante(), c.restante());
            aplicarEvento(terminal);
            assertEquals(c.esperado(), saldo(alvo), c.nome());
            assertEquals("ENCERRADA:0", estadoReserva(alvo), c.nome());
            assertEquals("TERMINAL_APLICADO::", estado(alvo), c.nome());
            assertEquals(derivado("matching:" + br.prospero.mo.cancelamento.helper.EnvelopeCancelamento.comando(alvo.ordem()) + ":0"),
                    escalar("SELECT terminal FROM cancelamento_operacao WHERE ordem=?1", alvo.ordem()), c.nome());
            aplicarEvento(terminal);
            assertEquals(c.esperado(), saldo(alvo), c.nome() + " reentrega");
            assertEquals(1, contar("matching_evento", "TRUE"), c.nome());
            assertEquals(0, emitidos("RESULTADO_CANCELAMENTO", alvo), "o resultado só sai com a decisão do ME");
        }
    }

    @Test
    void terminalIncoerenteBloqueiaSemLiberarNemMudarAOperacao() {
        Alvo alvo = reserva(true, 6, Recibo.CONFIRMADO);
        solicitar(alvo);
        String saldo = saldo(alvo);
        assertThrows(MensagemInvalidaException.class, () -> aplicarEvento(terminal(alvo, 4, 5)));
        assertThrows(MensagemInvalidaException.class, () -> aplicarEvento(terminal(alvo, 3, 6)));
        assertEquals(saldo, saldo(alvo));
        assertEquals("COMANDO_EMITIDO::", estado(alvo));
        assertEquals("PARCIAL:6", estadoReserva(alvo));
        assertEquals(0, contar("matching_evento", "TRUE"));
    }

    @Test
    void terminalAntesDoComandoOuDuplicadoComOutroIdBloqueia() {
        Alvo espera = reserva(false, 10, Recibo.PENDENTE);
        solicitar(espera);
        assertThrows(MensagemInvalidaException.class, () -> aplicarEvento(terminal(espera, 0, 10)));
        assertEquals("100/90/10", saldo(espera));
        limpar();
        Alvo alvo = reserva(false, 10, Recibo.CONFIRMADO);
        solicitar(alvo);
        aplicarEvento(terminal(alvo, 0, 10));
        // Com o terminal já aplicado, uma recusa do ME é incoerente: bloqueia e nunca libera de novo.
        assertThrows(MensagemInvalidaException.class, () -> aplicarEvento(decidido(alvo, "SEM_SALDO_VIVO")));
        assertEquals("100/100/0", saldo(alvo));
    }

    // 9. APLICADO exige o terminal aplicado e confirma uma única vez.
    @Test
    void decisaoAplicadaSemTerminalBloqueiaEComTerminalConfirmaUmaVez() {
        Alvo alvo = reserva(false, 6, Recibo.CONFIRMADO);
        solicitar(alvo);
        assertThrows(MensagemInvalidaException.class, () -> aplicarEvento(decidido(alvo, "APLICADO")));
        assertEquals("COMANDO_EMITIDO::", estado(alvo));
        assertEquals(0, emitidos("RESULTADO_CANCELAMENTO", alvo));
        aplicarEvento(terminal(alvo, 4, 6));
        aplicarEvento(decidido(alvo, "APLICADO"));
        aplicarEvento(decidido(alvo, "APLICADO"));
        assertEquals("CONFIRMADO::APLICADO", estado(alvo));
        assertEquals(1, emitidos("RESULTADO_CANCELAMENTO", alvo));
        String payload = (String) escalar("SELECT payload FROM outbox WHERE tipo='RESULTADO_CANCELAMENTO'");
        assertTrue(payload.contains("\"resultado\":\"CONFIRMADO\",\"motivo\":null"), payload);
        assertEquals("96/96/0", saldo(alvo));
    }

    // 10. Recusas do ME: RECUSADO sem tocar reserva nem caixa.
    @Test
    void decisoesDeRecusaDoMatchingRecusamSemTocarNaReserva() {
        for (String resultado : List.of("SEM_SALDO_VIVO", "ALVO_DIVERGENTE", "ORIGEM_OCO")) {
            limpar();
            Alvo alvo = reserva(true, 6, Recibo.CONFIRMADO);
            solicitar(alvo);
            String saldo = saldo(alvo);
            aplicarEvento(decidido(alvo, resultado));
            aplicarEvento(decidido(alvo, resultado));
            assertEquals("RECUSADO:" + resultado + ":" + resultado, estado(alvo), resultado);
            assertEquals(saldo, saldo(alvo), resultado);
            assertEquals("PARCIAL:6", estadoReserva(alvo), resultado);
            assertEquals(1, emitidos("RESULTADO_CANCELAMENTO", alvo), resultado);
            String payload = (String) escalar("SELECT payload FROM outbox WHERE tipo='RESULTADO_CANCELAMENTO'");
            assertTrue(payload.contains("\"resultado\":\"RECUSADO\",\"motivo\":\"" + resultado + "\""), payload);
        }
    }

    // 11. v5 sem operação correspondente continua bloqueando, e a recusa local também bloqueia o ME.
    @Test
    void v5SemOperacaoOuSobOperacaoRecusadaLocalmenteBloqueia() {
        Alvo semOperacao = reserva(false, 10, Recibo.CONFIRMADO);
        for (byte[] evento : List.of(terminal(semOperacao, 0, 10), decidido(semOperacao, "APLICADO"))) {
            var erro = assertThrows(ParticaoBloqueadaException.class, () -> aplicarEvento(evento));
            assertEquals("CANCELAMENTO_SEM_COORDENACAO", erro.getMessage());
        }
        assertEquals("100/90/10", saldo(semOperacao));
        Alvo oco = ocoComRecibo();
        solicitar(oco);
        assertThrows(MensagemInvalidaException.class, () -> aplicarEvento(decidido(oco, "ORIGEM_OCO")));
        assertEquals("RECUSADO:ORIGEM_OCO:", estado(oco));
    }

    private Alvo ocoComRecibo() {
        UUID intencao = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                    INSERT INTO decisao_risco(intencao,evento,conta,ticker,lado,resultado,quantidade_reservada,versao_parametros,decidida_em)
                    VALUES(:i,:e,'CONTA-001','ALFA3','VENDA','RESERVADA',10,1,now())
                    """).setParameter("i", intencao).setParameter("e", UUID.randomUUID()).executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO reserva(intencao,conta,ticker,recurso,quantidade,estado,criada_em,admissao,validade,data_sessao,
                    quantidade_inicial,quantidade_restante,oco) VALUES(:i,'CONTA-001','ALFA3','CUSTODIA',10,'ATIVA',now(),:a,'DIA',
                    '2026-10-18',10,10,TRUE)
                    """).setParameter("i", intencao).setParameter("a", br.prospero.mo.risco.helper.EnvelopeAdmissao.id(intencao)).executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO admissao_publicacao(admissao,topico,tipo,chave,payload,criada_em,confirmada_em)
                    VALUES(:a,'matching.comandos.v1','ORDEM_ADMITIDA','ALFA3','{}',now(),now())
                    """).setParameter("a", br.prospero.mo.risco.helper.EnvelopeAdmissao.id(intencao)).executeUpdate();
        });
        return new Alvo(UUID.randomUUID(), intencao, "CONTA-001", false, 10);
    }
}
