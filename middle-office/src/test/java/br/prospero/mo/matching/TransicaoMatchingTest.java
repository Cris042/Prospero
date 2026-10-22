package br.prospero.mo.matching;

import br.prospero.mo.matching.dominio.FatoMatching;
import br.prospero.mo.matching.dominio.NegocioMatching;
import br.prospero.mo.matching.dominio.PernaOco;
import br.prospero.mo.matching.dominio.ReservaMatching;
import br.prospero.mo.matching.dominio.Transicao;
import br.prospero.mo.mensageria.dominio.MensagemInvalidaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Guardas e transições de reserva, perna OCO e negócio extraídas do repositório (sem banco). */
class TransicaoMatchingTest {
    private static final UUID INTENCAO = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ADMISSAO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OUTRA = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final BigDecimal DEZ = new BigDecimal("10.00");
    private static final BigDecimal ONZE = new BigDecimal("11.00");

    private static ReservaMatching reserva(String tipo, String validade, long inicial, long restante, String estado,
                                           BigDecimal gatilho, boolean acionada) {
        return new ReservaMatching(INTENCAO, UUID.randomUUID(), "CONTA-001", "ALFA3", "CAIXA", tipo, validade,
                LocalDate.of(2026, 10, 18), inicial, restante, DEZ, ONZE, new BigDecimal("110.00"), estado, gatilho,
                acionada, false);
    }

    private static ReservaMatching grupo() {
        return new ReservaMatching(INTENCAO, null, "CONTA-001", "ALFA3", "CUSTODIA", null, "DIA",
                LocalDate.of(2026, 10, 18), 10, 10, null, null, null, "ATIVA", null, false, true);
    }

    private static PernaOco perna(String papel, String tipo, long executada, long reduzida, boolean acionada,
                                  String estado) {
        return new PernaOco(UUID.randomUUID(), INTENCAO, papel, tipo, papel.equals("TAKE_PROFIT") ? ONZE : null,
                papel.equals("TAKE_PROFIT") ? null : DEZ, 10, executada, reduzida, 0, acionada, estado);
    }

    private static FatoMatching fato(String tipo) {
        return new FatoMatching(tipo, ADMISSAO, null, 0, 0, 0, 0, null, null, null, null);
    }

    private static FatoMatching encerrada(String motivo, long executada, long cancelada) {
        return new FatoMatching("ORDEM_ENCERRADA", ADMISSAO, motivo, executada, cancelada, 0, 0, null, null, null, null);
    }

    private static void recusa(String campo, Executable acao) {
        assertEquals("matching: " + campo, assertThrows(MensagemInvalidaException.class, acao).getMessage());
    }

    @Test
    void reservaSimplesDecideEfeitoPorFato() {
        ReservaMatching stop = reserva("STOP", "DIA", 10, 10, "ATIVA", DEZ, false);
        assertEquals(Transicao.NENHUMA, stop.transicao(new FatoMatching("STOP_PENDENTE", ADMISSAO, null, 0, 0, 0, 0,
                null, null, new BigDecimal("10.0"), null), true, ADMISSAO));
        assertEquals(Transicao.ACIONAR_STOP, stop.transicao(new FatoMatching("STOP_ACIONADO", ADMISSAO, null, 0, 0, 0,
                0, null, null, null, "MARKET"), true, ADMISSAO));
        assertEquals(Transicao.ENCERRAR, stop.transicao(new FatoMatching("ORDEM_REJEITADA", ADMISSAO,
                "LIMITE_STOPS_PENDENTES", 0, 0, 0, 0, null, null, null, null), true, ADMISSAO));
        ReservaMatching limit = reserva("LIMIT", "DIA", 10, 4, "PARCIAL", null, false);
        assertEquals(Transicao.ENCERRAR, limit.transicao(encerrada("EXPIRADA_DIA", 6, 4), false, ADMISSAO));
        assertEquals(Transicao.NENHUMA, limit.transicao(new FatoMatching("OFERTA_NO_LIVRO", ADMISSAO, null, 0, 0, 4, 0,
                DEZ, "COMPRA", null, null), false, ADMISSAO));
        assertEquals(Transicao.NENHUMA, limit.transicao(fato("ORDEM_ACEITA"), false, ADMISSAO));
    }

    @Test
    void reservaSimplesPreservaOrdemEMensagensDasGuardas() {
        ReservaMatching stop = reserva("STOP", "DIA", 10, 10, "ATIVA", DEZ, false);
        recusa("versao stop", () -> stop.transicao(fato("ORDEM_ACEITA"), false, OUTRA));
        recusa("alvo correlacao", () -> stop.transicao(fato("ORDEM_ACEITA"), true, OUTRA));
        recusa("tipo executavel", () -> stop.transicao(new FatoMatching("STOP_ACIONADO", ADMISSAO, null, 0, 0, 0, 0,
                null, null, null, "LIMIT"), true, ADMISSAO));
        recusa("terminal stop nao acionado", () -> stop.transicao(encerrada("SEM_LIQUIDEZ", 0, 10), true, ADMISSAO));
        recusa("cascata", () -> reserva("STOP", "DIA", 10, 10, "ATIVA", DEZ, true)
                .transicao(encerrada("LIMITE_CASCATA", 0, 10), true, ADMISSAO));
        ReservaMatching limit = reserva("LIMIT", "IOC", 10, 4, "PARCIAL", null, false);
        recusa("rejeicao correlacao", () -> limit.transicao(fato("ORDEM_REJEITADA"), false, OUTRA));
        recusa("executada", () -> limit.transicao(encerrada("SALDO_CANCELADO_IOC", 5, 4), false, ADMISSAO));
        recusa("cancelada", () -> limit.transicao(encerrada("SALDO_CANCELADO_IOC", 6, 5), false, ADMISSAO));
        recusa("expiracao validade", () -> limit.transicao(encerrada("EXPIRADA_DIA", 6, 4), false, ADMISSAO));
        recusa("terminal FOK", () -> limit.transicao(encerrada("FOK_SEM_LIQUIDEZ", 6, 4), false, ADMISSAO));
        recusa("execucao terminal", () -> limit.transicao(encerrada("EXECUTADA", 6, 4), false, ADMISSAO));
        recusa("oferta tipo", () -> limit.transicao(new FatoMatching("OFERTA_NO_LIVRO", ADMISSAO, null, 0, 0, 4, 0, DEZ,
                "COMPRA", null, null), false, ADMISSAO));
        recusa("reserva encerrada", () -> reserva("LIMIT", "DIA", 10, 0, "ENCERRADA", null, false)
                .transicao(fato("ORDEM_ACEITA"), false, ADMISSAO));
    }

    @Test
    void pernaConsultaIrmaSomenteQuandoAGuardaPrecisa() {
        AtomicInteger consultas = new AtomicInteger();
        PernaOco takeProfit = perna("TAKE_PROFIT", "LIMIT", 0, 0, false, "ATIVA");
        Supplier<PernaOco> irma = () -> {
            consultas.incrementAndGet();
            return takeProfit;
        };
        // TP não é stop: a guarda falha antes de carregar a irmã (mesma ordem de locks do repositório anterior).
        recusa("stop acionamento", () -> takeProfit.transicao(new FatoMatching("STOP_ACIONADO", ADMISSAO, null, 0, 0, 0,
                0, null, null, null, "MARKET"), grupo(), ADMISSAO, irma));
        assertEquals(0, consultas.get());
        PernaOco stopLoss = perna("STOP_LOSS", "STOP", 0, 0, false, "ATIVA");
        recusa("stop acionamento", () -> stopLoss.transicao(new FatoMatching("STOP_ACIONADO", ADMISSAO, null, 0, 0, 0,
                0, null, null, null, "MARKET"), grupo(), ADMISSAO, irma));
        assertEquals(1, consultas.get(), "TP ainda vivo impede acionar o SL");
        assertEquals(Transicao.REDUZIR, stopLoss.transicao(new FatoMatching("ORDEM_REDUZIDA", ADMISSAO, null, 0, 0, 7,
                3, null, null, null, null), grupo(), ADMISSAO, () -> perna("TAKE_PROFIT", "LIMIT", 3, 0, false,
                "PARCIAL")));
        assertEquals(Transicao.ENCERRAR, stopLoss.transicao(encerrada("CANCELADA_OCO", 0, 10), grupo(), ADMISSAO,
                () -> perna("TAKE_PROFIT", "LIMIT", 10, 0, false, "ENCERRADA")));
        assertEquals(Transicao.NENHUMA, takeProfit.transicao(new FatoMatching("OFERTA_NO_LIVRO", ADMISSAO, null, 0, 0,
                10, 0, ONZE, "VENDA", null, null), grupo(), ADMISSAO, irma));
        recusa("alvo correlacao", () -> takeProfit.transicao(fato("ORDEM_ACEITA"), grupo(), OUTRA, irma));
        recusa("oferta lado", () -> takeProfit.transicao(new FatoMatching("OFERTA_NO_LIVRO", ADMISSAO, null, 0, 0, 10,
                0, ONZE, "COMPRA", null, null), grupo(), ADMISSAO, irma));
        recusa("terminal perna", () -> takeProfit.transicao(encerrada("SEM_LIQUIDEZ", 0, 10), grupo(), ADMISSAO, irma));
        assertThrows(IllegalStateException.class, () -> takeProfit.transicao(fato("NEGOCIO"), grupo(), ADMISSAO, irma));
    }

    @Test
    void negocioVerificaAcionamentoAntesDeRestanteTetoELimites() {
        ReservaMatching compra = reserva("LIMIT", "DIA", 10, 10, "ATIVA", null, false);
        ReservaMatching venda = new ReservaMatching(OUTRA, UUID.randomUUID(), "CONTA-002", "ALFA3", "CUSTODIA",
                "STOP", "DIA", LocalDate.of(2026, 10, 18), 10, 10, null, null, null, "ATIVA", DEZ, false, false);
        recusa("stop nao acionado", () -> new NegocioMatching(compra, venda, null, null, 20, DEZ).exigirExecutavel(true));
        ReservaMatching acionada = new ReservaMatching(OUTRA, UUID.randomUUID(), "CONTA-002", "ALFA3", "CUSTODIA",
                "STOP", "DIA", LocalDate.of(2026, 10, 18), 10, 10, null, null, null, "ATIVA", DEZ, true, false);
        recusa("stop nao acionado", () -> new NegocioMatching(compra, acionada, null, null, 1, DEZ).exigirExecutavel(false));
        recusa("restante", () -> new NegocioMatching(compra, acionada, null, null, 20, DEZ).exigirExecutavel(true));
        recusa("teto compra", () -> new NegocioMatching(compra, acionada, null, null, 1, new BigDecimal("12.00"))
                .exigirExecutavel(true));
        recusa("limite compra", () -> new NegocioMatching(compra, acionada, null, null, 1, ONZE).exigirExecutavel(true));
        new NegocioMatching(compra, acionada, null, null, 10, DEZ).exigirExecutavel(true);
        PernaOco takeProfit = perna("TAKE_PROFIT", "LIMIT", 8, 0, false, "PARCIAL");
        recusa("restante perna", () -> new NegocioMatching(compra, grupo(), null, takeProfit, 3, ONZE)
                .exigirExecutavel(true));
    }
}
