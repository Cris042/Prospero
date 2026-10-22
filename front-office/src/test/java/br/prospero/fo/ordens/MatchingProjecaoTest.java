package br.prospero.fo.ordens;

import br.prospero.fo.comum.dominio.MensagemInvalidaException;
import br.prospero.fo.ordens.dominio.*;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import br.prospero.fo.ordens.helper.ProjetarMatching;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MatchingProjecaoTest {
    private static final LocalDate SESSAO = LocalDate.of(2026, 10, 18);
    private static final BigDecimal TICK = new BigDecimal("0.01");

    private Ordem recebida() {
        UUID id = UUID.randomUUID();
        return new Ordem(id, "CONTA-002", "ALFA3", LadoOrdem.COMPRA, TipoOrdem.LIMIT, Validade.IOC,
                10, new BigDecimal("10.00"), null, null, null, id, EstadoOrdem.RECEBIDA, SESSAO,
                Instant.parse("2026-10-18T12:00:00Z"), null);
    }

    private EventoMatching.Alvo alvo(Ordem o) {
        return new EventoMatching.Alvo(o.id(), o.grupoEnvio(), o.conta());
    }

    private EventoMatching evento(EventoMatching.Detalhe detalhe) {
        return new EventoMatching(UUID.randomUUID(), UUID.randomUUID(), "ALFA3", SESSAO, "assinatura", detalhe);
    }

    private Ordem projetar(Ordem o, EventoMatching.Detalhe detalhe) {
        return ProjetarMatching.aplicar(o, evento(detalhe), alvo(o), TICK, 1);
    }

    private EventoMatching.Negocio negocio(Ordem o, long quantidade, String preco) {
        return new EventoMatching.Negocio("ALFA3-2026-10-18-1", new BigDecimal(preco), quantidade, alvo(o),
                new EventoMatching.Alvo(UUID.randomUUID(), UUID.randomUUID(), "CONTA-003"), LadoOrdem.COMPRA);
    }

    @Test
    void matchingAntesDoRiscoAvancaParcialECancelaSomenteSaldoSemRegredirPorAceiteAntigo() {
        Ordem o = recebida();
        o = projetar(o, negocio(o, 4, "9.50"));
        assertEquals(EstadoOrdem.PARCIALMENTE_EXECUTADA, o.estado());
        assertEquals(4, o.quantidadeExecutada());
        o = projetar(o, new EventoMatching.Aceita(alvo(o), 1));
        assertEquals(EstadoOrdem.PARCIALMENTE_EXECUTADA, o.estado());
        o = projetar(o, new EventoMatching.Encerrada(alvo(o), "SALDO_CANCELADO_IOC", 4, 6));
        assertEquals(EstadoOrdem.CANCELADA, o.estado());
        assertEquals(4, o.quantidadeExecutada());
        assertEquals(6, o.quantidadeCancelada());
        assertEquals("SALDO_CANCELADO_IOC", o.motivoEncerramento());
        assertEquals(o, projetar(o, new EventoMatching.Aceita(alvo(o), 1)));
    }

    @Test
    void execucaoTotalNaoDependeDeResultadoRiscoERecusaNegocioAdicional() {
        Ordem inicial = recebida();
        Ordem finalizada = projetar(inicial, negocio(inicial, 10, "10.00"));
        assertEquals(EstadoOrdem.EXECUTADA, finalizada.estado());
        assertThrows(MatchingIncoerenteException.class, () -> projetar(finalizada, negocio(finalizada, 1, "10.00")));
    }

    @Test
    void negocioAcimaDoLimiteOuTerminalInconsistenteNaoAlteraOrdem() {
        Ordem o = recebida();
        assertThrows(MatchingIncoerenteException.class, () -> projetar(o, negocio(o, 1, "10.01")));
        assertThrows(MatchingIncoerenteException.class,
                () -> projetar(o, new EventoMatching.Encerrada(alvo(o), "EXECUTADA", 0, 10)));
        assertEquals(EstadoOrdem.RECEBIDA, o.estado());
    }

    @Test
    void motivoFokNaoPodeCancelarIoc() {
        Ordem o = recebida();
        assertThrows(MatchingIncoerenteException.class,
                () -> projetar(o, new EventoMatching.Encerrada(alvo(o), "FOK_SEM_LIQUIDEZ", 0, 10)));
    }

    @Test
    void decoderRecusaChavesExtrasDuplicadasOrigemVersaoETrailingTokens() {
        Ordem o = recebida();
        String valido = """
                {"id":"%s","tipo":"ORDEM_ACEITA","versao":1,"origem":"matching-engine",
                "correlacao":"%s","ocorridoEm":"2026-10-18T12:00:00Z","ticker":"ALFA3",
                "dataSessao":"2026-10-18","traceparent":null,"dados":{"ordem":"%s",
                "intencao":"%s","conta":"CONTA-002","sequencia":1}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), o.id(), o.grupoEnvio());
        assertInstanceOf(EventoMatching.Aceita.class, EnvelopeMatching.evento(valido.getBytes(StandardCharsets.UTF_8), "ALFA3").detalhe());
        for (String invalido : new String[]{valido.replace("\"versao\":1", "\"versao\":2"),
                valido.replace("matching-engine", "middle-office"), valido + " {}",
                valido.replace("\"sequencia\":1", "\"sequencia\":1,\"extra\":0"),
                valido.replace("\"versao\":1", "\"versao\":1,\"versao\":1")}) {
            assertThrows(MensagemInvalidaException.class,
                    () -> EnvelopeMatching.evento(invalido.getBytes(StandardCharsets.UTF_8), "ALFA3"));
        }
        assertThrows(MensagemInvalidaException.class,
                () -> EnvelopeMatching.evento(valido.getBytes(StandardCharsets.UTF_8), "BETA3"));
    }

    @Test
    void contaDoRoboFormadorEntraNaAllowlistEContaInexistenteContinuaRecusada() {
        Ordem o = recebida();
        String evento = """
                {"id":"%s","tipo":"ORDEM_ACEITA","versao":1,"origem":"matching-engine",
                "correlacao":"%s","ocorridoEm":"2026-10-18T12:00:00Z","ticker":"ALFA3",
                "dataSessao":"2026-10-18","traceparent":null,"dados":{"ordem":"%s",
                "intencao":"%s","conta":"CONTA-004","sequencia":1}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), o.id(), o.grupoEnvio());
        assertInstanceOf(EventoMatching.Aceita.class, EnvelopeMatching.evento(evento.getBytes(StandardCharsets.UTF_8), "ALFA3").detalhe());
        assertThrows(MensagemInvalidaException.class, () -> EnvelopeMatching.evento(
                evento.replace("CONTA-004", "CONTA-005").getBytes(StandardCharsets.UTF_8), "ALFA3"));
    }
}
