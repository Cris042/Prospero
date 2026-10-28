package br.prospero.fo.ordens;

import br.prospero.fo.comum.ContratosV5;
import br.prospero.fo.comum.dominio.MensagemInvalidaException;
import br.prospero.fo.cotacao.dominio.MutacaoCotacao;
import br.prospero.fo.cotacao.helper.ProjetarCotacao;
import br.prospero.fo.ordens.dominio.*;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import br.prospero.fo.ordens.helper.ProjetarMatching;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RF10c2a: leitores FO do envelope v5 (ordens, trajetória e cotação) contra as fixtures compartilhadas. */
class CancelamentoManualLeitorTest {
    private static final String TOPICO = "matching.eventos.v1";
    private static final UUID ORDEM = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID INTENCAO = UUID.fromString("b0000000-0000-4000-8000-000000000001");

    private static EventoMatching fixture(String nome) throws Exception {
        return EnvelopeMatching.evento(ContratosV5.ler(TOPICO + "/ALFA3/" + nome + ".json"), "ALFA3");
    }

    private static Ordem ordem(TipoOrdem tipo, EstadoOrdem estado, long executada, PapelOco papel, String conta) {
        return new Ordem(ORDEM, conta, "ALFA3", LadoOrdem.VENDA, tipo, Validade.DIA, 10,
                tipo == TipoOrdem.STOP ? null : new BigDecimal("10.00"), tipo == TipoOrdem.STOP ? new BigDecimal("9.00") : null,
                papel == null ? null : ORDEM, papel, INTENCAO, estado, LocalDate.of(2026, 10, 18),
                Instant.parse("2026-10-18T12:00:00Z"), null, null, executada, 0, null, 0);
    }

    private static Ordem aplicar(Ordem o, EventoMatching e) {
        var alvo = switch (e.detalhe()) {
            case EventoMatching.Encerrada f -> f.alvo();
            case EventoMatching.CancelamentoDecidido d -> d.alvo();
            default -> throw new IllegalStateException();
        };
        return ProjetarMatching.aplicar(o, e, alvo, new BigDecimal("0.01"), 1);
    }

    @Test
    void terminalValidoViraEncerradaCanceladaManualNaV5() throws Exception {
        var e = fixture("ordem-encerrada-cancelada-manual");
        assertEquals(5, e.versao());
        assertEquals(EventoMatching.comandoCancelamento(ORDEM), e.causacao());
        var f = assertInstanceOf(EventoMatching.Encerrada.class, e.detalhe());
        assertEquals("CANCELADA_MANUAL", f.motivo());
        assertEquals(4, f.executada());
        assertEquals(6, f.cancelada());
    }

    @Test
    void decisoesValidasViramCancelamentoDecidido() throws Exception {
        var aplicado = assertInstanceOf(EventoMatching.CancelamentoDecidido.class, fixture("cancelamento-decidido-aplicado").detalhe());
        assertEquals(EventoMatching.ResultadoCancelamento.APLICADO, aplicado.resultado());
        assertEquals(fixture("ordem-encerrada-cancelada-manual").id(), aplicado.terminal());
        for (String nome : List.of("sem-saldo-vivo", "alvo-divergente", "origem-oco")) {
            var recusa = assertInstanceOf(EventoMatching.CancelamentoDecidido.class,
                    fixture("cancelamento-decidido-" + nome).detalhe());
            assertNull(recusa.terminal(), nome);
        }
    }

    @Test
    void corpusNegativoFalhaFechadoSemVazarOPayload() throws Exception {
        var invalidos = ContratosV5.casos(TOPICO, true);
        assertTrue(invalidos.size() >= 40, "corpus negativo de eventos v5");
        for (var caso : invalidos) {
            var erro = assertThrows(MensagemInvalidaException.class, () -> EnvelopeMatching.evento(caso.bytes(), caso.chave()), caso.nome());
            assertTrue(erro.getMessage().startsWith("matching: "), caso.nome());
            assertFalse(erro.getMessage().contains("forjado") || erro.getMessage().contains("\n"), caso.nome());
        }
    }

    @Test
    void tickerDiferenteDaChaveKafkaEhInvalido() throws Exception {
        var bytes = ContratosV5.ler(TOPICO + "/ALFA3/cancelamento-decidido-aplicado.json");
        assertThrows(MensagemInvalidaException.class, () -> EnvelopeMatching.evento(bytes, "BETA3"));
    }

    @Test
    void replayMistoDeEnvelopesHistoricosV1aV4ComV5() throws Exception {
        int historicos = 0;
        for (int v = 1; v <= 4; v++) {
            var arquivo = java.nio.file.Path.of("..", "matching-engine", "src", "test", "resources", "rf10a", "historico-v" + v + "-envelopes.ndjson");
            for (String linha : java.nio.file.Files.readAllLines(arquivo, StandardCharsets.UTF_8)) {
                var e = EnvelopeMatching.evento(linha.substring(linha.indexOf('\t') + 1).getBytes(StandardCharsets.UTF_8), "ALFA3");
                assertTrue(e.versao() <= v, "v" + v);
                historicos++;
                for (String nome : List.of("ordem-encerrada-cancelada-manual", "cancelamento-decidido-aplicado")) {
                    assertEquals(5, fixture(nome).versao());
                }
            }
        }
        assertTrue(historicos > 50);
    }

    @Test
    void terminalManualCancelaLimitParcialEStopNaoAcionado() throws Exception {
        var e = fixture("ordem-encerrada-cancelada-manual");
        Ordem limit = aplicar(ordem(TipoOrdem.LIMIT, EstadoOrdem.PARCIALMENTE_EXECUTADA, 4, null, "CONTA-001"), e);
        assertEquals(EstadoOrdem.CANCELADA, limit.estado());
        assertEquals("CANCELADA_MANUAL", limit.motivoEncerramento());
        assertEquals(6, limit.quantidadeCancelada());
        assertEquals(4, limit.quantidadeExecutada());
        var integral = mudarSaldo(e, 0, 10);
        Ordem stop = aplicar(ordem(TipoOrdem.STOP, EstadoOrdem.AGUARDANDO_GATILHO, 0, null, "CONTA-001"), integral);
        assertEquals(EstadoOrdem.CANCELADA, stop.estado());
        assertEquals(10, stop.quantidadeCancelada());
        Ordem stopLimit = aplicar(ordem(TipoOrdem.STOP_LIMIT, EstadoOrdem.ABERTA, 0, null, "CONTA-001"), integral);
        assertEquals(EstadoOrdem.CANCELADA, stopLimit.estado(), "Stop Limit acionado com saldo");
    }

    @Test
    void terminalManualBloqueiaOcoSaldoDivergenteEEstadoFinal() throws Exception {
        var e = fixture("ordem-encerrada-cancelada-manual");
        assertThrows(MatchingIncoerenteException.class, () -> aplicar(
                ordem(TipoOrdem.LIMIT, EstadoOrdem.PARCIALMENTE_EXECUTADA, 4, PapelOco.TAKE_PROFIT, "CONTA-001"), e), "OCO");
        assertThrows(MatchingIncoerenteException.class, () -> aplicar(
                ordem(TipoOrdem.LIMIT, EstadoOrdem.PARCIALMENTE_EXECUTADA, 3, null, "CONTA-001"), e), "executada divergente");
        assertThrows(MatchingIncoerenteException.class, () -> aplicar(
                ordem(TipoOrdem.LIMIT, EstadoOrdem.PARCIALMENTE_EXECUTADA, 4, null, "CONTA-002"), e), "conta divergente");
        assertThrows(MatchingIncoerenteException.class, () -> aplicar(
                ordem(TipoOrdem.LIMIT, EstadoOrdem.EXECUTADA, 10, null, "CONTA-001"), mudarSaldo(e, 10, 1)), "já executada");
        Ordem cancelada = new Ordem(ORDEM, "CONTA-001", "ALFA3", LadoOrdem.VENDA, TipoOrdem.LIMIT, Validade.DIA, 10,
                new BigDecimal("10.00"), null, null, null, INTENCAO, EstadoOrdem.CANCELADA, LocalDate.of(2026, 10, 18),
                Instant.parse("2026-10-18T12:00:00Z"), null, null, 4, 6, "EXPIRADA_DIA", 0);
        assertThrows(MatchingIncoerenteException.class, () -> aplicar(cancelada, e), "cancelada por outro motivo");
    }

    @Test
    void terminalManualRepetidoNaMesmaOrdemEhIdempotente() throws Exception {
        var e = fixture("ordem-encerrada-cancelada-manual");
        Ordem uma = aplicar(ordem(TipoOrdem.LIMIT, EstadoOrdem.PARCIALMENTE_EXECUTADA, 4, null, "CONTA-001"), e);
        assertEquals(uma, aplicar(uma, e));
    }

    @Test
    void cotacaoRetiraTerminalManualEIgnoraADecisao() throws Exception {
        var terminal = fixture("ordem-encerrada-cancelada-manual");
        assertEquals(List.of(new MutacaoCotacao.Retirar(ORDEM)), ProjetarCotacao.mutacoes(terminal.detalhe()));
        for (String nome : List.of("aplicado", "sem-saldo-vivo", "alvo-divergente", "origem-oco")) {
            assertEquals(List.of(), ProjetarCotacao.mutacoes(fixture("cancelamento-decidido-" + nome).detalhe()), nome);
        }
    }

    /** Reescreve executada/cancelada do terminal, preservando o restante do evento já validado. */
    private static EventoMatching mudarSaldo(EventoMatching e, long executada, long cancelada) {
        var f = (EventoMatching.Encerrada) e.detalhe();
        return new EventoMatching(e.id(), e.correlacao(), e.ticker(), e.sessao(), e.assinatura(),
                new EventoMatching.Encerrada(f.alvo(), f.motivo(), executada, cancelada), e.contexto());
    }
}
