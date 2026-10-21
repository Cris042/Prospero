package br.prospero.fo.ordens;

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

/** QA independente da RF05c.3b1: ordens de chegada e terminais das pernas OCO na projeção do FO. */
class OcoProjecaoQaTest {
    private static final UUID GRUPO = UUID.fromString("5f0b3c1e-6a1d-4f7e-9d43-2b8f6c0a9e11");
    private static final UUID TP = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID SL = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private static Ordem perna(PapelOco papel, TipoOrdem tipo) {
        boolean tp = papel == PapelOco.TAKE_PROFIT;
        return new Ordem(tp ? TP : SL, "CONTA-003", "ALFA3", LadoOrdem.VENDA, tipo, Validade.DIA, 10,
                tp ? new BigDecimal("12.00") : tipo == TipoOrdem.STOP_LIMIT ? new BigDecimal("8.90") : null,
                tp ? null : new BigDecimal("9.00"), GRUPO, papel, GRUPO, EstadoOrdem.RESERVADA, LocalDate.of(2026, 10, 18),
                Instant.parse("2026-10-18T12:00:00Z"), null);
    }

    private static Ordem tp() { return perna(PapelOco.TAKE_PROFIT, TipoOrdem.LIMIT); }
    private static Ordem sl() { return perna(PapelOco.STOP_LOSS, TipoOrdem.STOP); }

    private static EventoMatching evento(int versao, String tipo, UUID ordem, String campos) {
        String s = """
                {"id":"%s","tipo":"%s","versao":%d,"origem":"matching-engine",
                "correlacao":"%s","causacao":"ed42a1e3-9ef9-4ba5-87b4-a0bcf224a316",
                "ocorridoEm":"2026-10-18T12:00:00Z","ticker":"ALFA3","dataSessao":"2026-10-18","traceparent":null,
                "dados":{"ordem":"%s","intencao":"%s","conta":"CONTA-003",%s}}
                """.formatted(UUID.randomUUID(), tipo, versao, GRUPO, ordem, GRUPO, campos);
        return EnvelopeMatching.evento(s.getBytes(StandardCharsets.UTF_8), "ALFA3");
    }

    private static EventoMatching v3(String tipo, UUID ordem, String campos) { return evento(3, tipo, ordem, campos); }

    private static Ordem aplicar(Ordem o, EventoMatching e) {
        return ProjetarMatching.aplicar(o, e, new EventoMatching.Alvo(o.id(), GRUPO, "CONTA-003"), new BigDecimal("0.01"), 1);
    }

    private static EventoMatching negocio(UUID venda, long quantidade, String preco) {
        return new EventoMatching(UUID.randomUUID(), UUID.randomUUID(), "ALFA3", LocalDate.of(2026, 10, 18), "h",
                new EventoMatching.Negocio("ALFA3-2026-10-18-1", new BigDecimal(preco), quantidade,
                        new EventoMatching.Alvo(UUID.randomUUID(), UUID.randomUUID(), "CONTA-002"),
                        new EventoMatching.Alvo(venda, GRUPO, "CONTA-003"), LadoOrdem.COMPRA), UUID.randomUUID(), 3);
    }

    /** ADR-013/ME: com 500 pendentes o núcleo rejeita as duas pernas com LIMITE_STOPS_PENDENTES (TP primeiro, v3). */
    @Test
    void rejeicaoDoParPorLimiteDePendentesProjetaAsDuasPernas() {
        Ordem t = aplicar(tp(), v3("ORDEM_REJEITADA", TP, "\"motivo\":\"LIMITE_STOPS_PENDENTES\""));
        assertEquals(EstadoOrdem.REJEITADA, t.estado());
        Ordem s = aplicar(sl(), v3("ORDEM_REJEITADA", SL, "\"motivo\":\"LIMITE_STOPS_PENDENTES\""));
        assertEquals(EstadoOrdem.REJEITADA, s.estado());
    }

    @Test
    void reducaoAntesDoStopPendenteMantemAguardandoGatilhoEReducao() {
        Ordem s = aplicar(sl(), v3("ORDEM_ACEITA", SL, "\"sequencia\":2"));
        s = aplicar(s, v3("ORDEM_REDUZIDA", SL, "\"reduzida\":3,\"restante\":7"));
        s = aplicar(s, v3("STOP_PENDENTE", SL, "\"precoGatilho\":\"9.00\""));
        assertEquals(EstadoOrdem.AGUARDANDO_GATILHO, s.estado());
        assertEquals(3, s.quantidadeReduzida());
        assertEquals(7, s.quantidadeVigente());
    }

    @Test
    void reducaoReentregueComIdNovoOuAposAcionamentoERecusada() {
        Ordem s = aplicar(sl(), v3("ORDEM_ACEITA", SL, "\"sequencia\":2"));
        Ordem reduzido = aplicar(s, v3("ORDEM_REDUZIDA", SL, "\"reduzida\":4,\"restante\":6"));
        assertThrows(MatchingIncoerenteException.class,
                () -> aplicar(reduzido, v3("ORDEM_REDUZIDA", SL, "\"reduzida\":4,\"restante\":6")), "id novo, mesmo conteúdo");
        Ordem acionado = aplicar(reduzido, v3("STOP_ACIONADO", SL, "\"tipoExecutavel\":\"MARKET\",\"sequencia\":3"));
        assertEquals(EstadoOrdem.ABERTA, acionado.estado());
        assertThrows(MatchingIncoerenteException.class,
                () -> aplicar(acionado, v3("ORDEM_REDUZIDA", SL, "\"reduzida\":1,\"restante\":5")), "SL acionado não reduz");
        Ordem parcial = aplicar(acionado, negocio(SL, 2, "8.50"));
        Ordem fim = aplicar(parcial, v3("ORDEM_ENCERRADA", SL, "\"motivo\":\"SEM_LIQUIDEZ\",\"executada\":2,\"cancelada\":4"));
        assertEquals(EstadoOrdem.CANCELADA, fim.estado());
        assertEquals(2 + 4 + 4, fim.quantidadeExecutada() + fim.quantidadeCancelada() + fim.quantidadeReduzida(), "conservação");
        assertThrows(MatchingIncoerenteException.class, () -> aplicar(parcial,
                v3("ORDEM_ENCERRADA", SL, "\"motivo\":\"SEM_LIQUIDEZ\",\"executada\":2,\"cancelada\":8")), "cancelada usa vigente");
    }

    @Test
    void limiteCascataDoSlReduzidoCancelaSoAVigente() {
        Ordem s = aplicar(sl(), v3("ORDEM_ACEITA", SL, "\"sequencia\":2"));
        s = aplicar(s, v3("ORDEM_REDUZIDA", SL, "\"reduzida\":4,\"restante\":6"));
        Ordem reduzido = s;
        assertThrows(MatchingIncoerenteException.class, () -> aplicar(reduzido,
                v3("ORDEM_ENCERRADA", SL, "\"motivo\":\"LIMITE_CASCATA\",\"executada\":0,\"cancelada\":10")));
        Ordem fim = aplicar(reduzido, v3("ORDEM_ENCERRADA", SL, "\"motivo\":\"LIMITE_CASCATA\",\"executada\":0,\"cancelada\":6"));
        assertEquals(EstadoOrdem.CANCELADA, fim.estado());
        assertEquals("LIMITE_CASCATA", fim.motivoEncerramento());
    }

    @Test
    void tpIntegralEmDuasExecucoesESlCanceladoComSaldoReduzido() {
        Ordem t = aplicar(tp(), v3("ORDEM_ACEITA", TP, "\"sequencia\":1"));
        t = aplicar(t, negocio(TP, 4, "12.00"));
        t = aplicar(t, negocio(TP, 6, "12.00"));
        assertEquals(EstadoOrdem.EXECUTADA, t.estado());
        t = aplicar(t, v3("ORDEM_ENCERRADA", TP, "\"motivo\":\"EXECUTADA\",\"executada\":10,\"cancelada\":0"));
        assertEquals(EstadoOrdem.EXECUTADA, t.estado());
        Ordem s = aplicar(sl(), v3("ORDEM_ACEITA", SL, "\"sequencia\":2"));
        s = aplicar(s, v3("ORDEM_REDUZIDA", SL, "\"reduzida\":4,\"restante\":6"));
        s = aplicar(s, v3("ORDEM_ENCERRADA", SL, "\"motivo\":\"CANCELADA_OCO\",\"executada\":0,\"cancelada\":6"));
        assertEquals(EstadoOrdem.CANCELADA, s.estado());
        Ordem cancelado = s;
        Ordem tardio = aplicar(cancelado, v3("STOP_ACIONADO", SL, "\"tipoExecutavel\":\"MARKET\",\"sequencia\":9"));
        assertEquals(EstadoOrdem.CANCELADA, tardio.estado(), "acionamento tardio não reabre a perna cancelada");
        assertThrows(MatchingIncoerenteException.class, () -> aplicar(cancelado, negocio(SL, 1, "8.00")));
    }

    @Test
    void pernaOcoRecusaQualquerFatoForaDaV3() {
        assertThrows(MatchingIncoerenteException.class, () -> aplicar(sl(), evento(2, "ORDEM_ACEITA", SL, "\"sequencia\":2")));
        assertThrows(MatchingIncoerenteException.class, () -> aplicar(sl(), evento(2, "STOP_PENDENTE", SL, "\"precoGatilho\":\"9.00\"")));
    }
}
