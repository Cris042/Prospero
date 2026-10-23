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
        return FatoMatching.semCampos(tipo, ADMISSAO);
    }

    private static FatoMatching encerrada(String motivo, long executada, long cancelada) {
        return FatoMatching.ordemEncerrada(ADMISSAO, motivo, executada, cancelada);
    }

    private static void recusa(String campo, Executable acao) {
        assertEquals("matching: " + campo, assertThrows(MensagemInvalidaException.class, acao).getMessage());
    }

    @Test
    void reservaSimplesDecideEfeitoPorFato() {
        ReservaMatching stop = reserva("STOP", "DIA", 10, 10, "ATIVA", DEZ, false);
        assertEquals(Transicao.NENHUMA, stop.transicao(FatoMatching.stopPendente(ADMISSAO, new BigDecimal("10.0")),
                true, ADMISSAO));
        assertEquals(Transicao.ACIONAR_STOP, stop.transicao(FatoMatching.stopAcionado(ADMISSAO, "MARKET"), true,
                ADMISSAO));
        assertEquals(Transicao.ENCERRAR, stop.transicao(FatoMatching.ordemRejeitada(ADMISSAO,
                "LIMITE_STOPS_PENDENTES"), true, ADMISSAO));
        ReservaMatching limit = reserva("LIMIT", "DIA", 10, 4, "PARCIAL", null, false);
        assertEquals(Transicao.ENCERRAR, limit.transicao(encerrada("EXPIRADA_DIA", 6, 4), false, ADMISSAO));
        assertEquals(Transicao.NENHUMA, limit.transicao(FatoMatching.ofertaNoLivro(ADMISSAO, 4, DEZ, "COMPRA"), false,
                ADMISSAO));
        assertEquals(Transicao.NENHUMA, limit.transicao(fato("ORDEM_ACEITA"), false, ADMISSAO));
    }

    @Test
    void reservaSimplesPreservaOrdemEMensagensDasGuardas() {
        ReservaMatching stop = reserva("STOP", "DIA", 10, 10, "ATIVA", DEZ, false);
        recusa("versao stop", () -> stop.transicao(fato("ORDEM_ACEITA"), false, OUTRA));
        recusa("alvo correlacao", () -> stop.transicao(fato("ORDEM_ACEITA"), true, OUTRA));
        recusa("tipo executavel", () -> stop.transicao(FatoMatching.stopAcionado(ADMISSAO, "LIMIT"), true, ADMISSAO));
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
        recusa("oferta tipo", () -> limit.transicao(FatoMatching.ofertaNoLivro(ADMISSAO, 4, DEZ, "COMPRA"), false,
                ADMISSAO));
        recusa("reserva encerrada", () -> reserva("LIMIT", "DIA", 10, 0, "ENCERRADA", null, false)
                .transicao(fato("ORDEM_ACEITA"), false, ADMISSAO));
    }

    @Test
    void reservaSimplesRecusaStopPendenteRejeicaoETerminalIocDivergentes() {
        ReservaMatching stop = reserva("STOP", "DIA", 10, 10, "ATIVA", DEZ, false);
        recusa("gatilho", () -> stop.transicao(FatoMatching.stopPendente(ADMISSAO, ONZE), true, ADMISSAO));
        ReservaMatching acionado = reserva("STOP", "DIA", 10, 10, "ATIVA", DEZ, true);
        recusa("stop pendente", () -> acionado.transicao(FatoMatching.stopPendente(ADMISSAO, DEZ), true, ADMISSAO));
        recusa("stop pendente", () -> reserva("LIMIT", "DIA", 10, 10, "ATIVA", null, false)
                .transicao(FatoMatching.stopPendente(ADMISSAO, DEZ), true, ADMISSAO));
        recusa("rejeicao apos acionamento", () -> acionado.transicao(FatoMatching.ordemRejeitada(ADMISSAO,
                "LIMITE_ORDENS_ATIVAS"), true, ADMISSAO));
        recusa("rejeicao stop", () -> reserva("LIMIT", "DIA", 10, 10, "ATIVA", null, false)
                .transicao(FatoMatching.ordemRejeitada(ADMISSAO, "LIMITE_STOPS_PENDENTES"), true, ADMISSAO));
        // IOC só fora de validade IOC quando é Stop (Market) acionado; Limit DIA e Stop Limit acionado não.
        recusa("terminal IOC", () -> reserva("LIMIT", "DIA", 10, 4, "PARCIAL", null, false)
                .transicao(encerrada("SALDO_CANCELADO_IOC", 6, 4), false, ADMISSAO));
        recusa("terminal IOC", () -> reserva("STOP_LIMIT", "DIA", 10, 4, "PARCIAL", DEZ, true)
                .transicao(encerrada("SALDO_CANCELADO_IOC", 6, 4), true, ADMISSAO));
        assertEquals(Transicao.ENCERRAR, reserva("STOP", "DIA", 10, 4, "PARCIAL", DEZ, true)
                .transicao(encerrada("SALDO_CANCELADO_IOC", 6, 4), true, ADMISSAO));
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
        recusa("stop acionamento", () -> takeProfit.transicao(FatoMatching.stopAcionado(ADMISSAO, "MARKET"), grupo(),
                ADMISSAO, irma));
        assertEquals(0, consultas.get());
        PernaOco stopLoss = perna("STOP_LOSS", "STOP", 0, 0, false, "ATIVA");
        recusa("stop acionamento", () -> stopLoss.transicao(FatoMatching.stopAcionado(ADMISSAO, "MARKET"), grupo(),
                ADMISSAO, irma));
        assertEquals(1, consultas.get(), "TP ainda vivo impede acionar o SL");
        assertEquals(Transicao.REDUZIR, stopLoss.transicao(FatoMatching.ordemReduzida(ADMISSAO, 3, 7), grupo(),
                ADMISSAO, () -> perna("TAKE_PROFIT", "LIMIT", 3, 0, false, "PARCIAL")));
        assertEquals(Transicao.ENCERRAR, stopLoss.transicao(encerrada("CANCELADA_OCO", 0, 10), grupo(), ADMISSAO,
                () -> perna("TAKE_PROFIT", "LIMIT", 10, 0, false, "ENCERRADA")));
        assertEquals(Transicao.NENHUMA, takeProfit.transicao(FatoMatching.ofertaNoLivro(ADMISSAO, 10, ONZE, "VENDA"),
                grupo(), ADMISSAO, irma));
        recusa("alvo correlacao", () -> takeProfit.transicao(fato("ORDEM_ACEITA"), grupo(), OUTRA, irma));
        recusa("oferta lado", () -> takeProfit.transicao(FatoMatching.ofertaNoLivro(ADMISSAO, 10, ONZE, "COMPRA"),
                grupo(), ADMISSAO, irma));
        recusa("terminal perna", () -> takeProfit.transicao(encerrada("SEM_LIQUIDEZ", 0, 10), grupo(), ADMISSAO, irma));
        assertThrows(IllegalStateException.class, () -> takeProfit.transicao(fato("NEGOCIO"), grupo(), ADMISSAO, irma));
    }

    @Test
    void pernaRecusaPendenteReducaoRejeicaoECascataDivergentes() {
        PernaOco takeProfitVivo = perna("TAKE_PROFIT", "LIMIT", 0, 0, false, "ATIVA");
        PernaOco takeProfitEncerrado = perna("TAKE_PROFIT", "LIMIT", 10, 0, false, "ENCERRADA");
        PernaOco stopLoss = perna("STOP_LOSS", "STOP", 0, 0, false, "ATIVA");
        recusa("gatilho", () -> stopLoss.transicao(FatoMatching.stopPendente(ADMISSAO, ONZE), grupo(), ADMISSAO,
                () -> takeProfitVivo));
        recusa("stop pendente", () -> takeProfitVivo.transicao(FatoMatching.stopPendente(ADMISSAO, DEZ), grupo(),
                ADMISSAO, () -> stopLoss));
        recusa("stop pendente", () -> perna("STOP_LOSS", "STOP", 0, 0, true, "ATIVA")
                .transicao(FatoMatching.stopPendente(ADMISSAO, DEZ), grupo(), ADMISSAO, () -> takeProfitVivo));
        // Restante após a redução (8) diverge de 10 - 3 e do saldo do TP (10 - 3).
        recusa("reducao restante", () -> stopLoss.transicao(FatoMatching.ordemReduzida(ADMISSAO, 3, 8), grupo(),
                ADMISSAO, () -> perna("TAKE_PROFIT", "LIMIT", 3, 0, false, "PARCIAL")));
        recusa("reducao restante", () -> stopLoss.transicao(FatoMatching.ordemReduzida(ADMISSAO, 10, 1), grupo(),
                ADMISSAO, () -> perna("TAKE_PROFIT", "LIMIT", 9, 0, false, "PARCIAL")));
        recusa("rejeicao stop", () -> takeProfitVivo.transicao(FatoMatching.ordemRejeitada(ADMISSAO,
                "LIMITE_STOPS_PENDENTES"), grupo(), ADMISSAO, () -> perna("STOP_LOSS", "STOP", 0, 0, false,
                "ENCERRADA")));
        recusa("cascata", () -> perna("STOP_LOSS", "STOP", 0, 0, true, "ATIVA")
                .transicao(encerrada("LIMITE_CASCATA", 0, 10), grupo(), ADMISSAO, () -> takeProfitEncerrado));
        recusa("cascata", () -> stopLoss.transicao(encerrada("LIMITE_CASCATA", 0, 10), grupo(), ADMISSAO,
                () -> takeProfitVivo));
        recusa("cancelamento oco", () -> takeProfitVivo.transicao(encerrada("CANCELADA_OCO", 0, 10), grupo(),
                ADMISSAO, () -> perna("STOP_LOSS", "STOP", 0, 0, false, "ENCERRADA")));
        recusa("cancelamento oco", () -> stopLoss.transicao(encerrada("CANCELADA_OCO", 0, 10), grupo(), ADMISSAO,
                () -> takeProfitVivo));
        recusa("terminal IOC", () -> perna("STOP_LOSS", "STOP_LIMIT", 0, 0, true, "ATIVA")
                .transicao(encerrada("SALDO_CANCELADO_IOC", 0, 10), grupo(), ADMISSAO, () -> takeProfitEncerrado));
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
        ReservaMatching vendaLimitada = new ReservaMatching(OUTRA, UUID.randomUUID(), "CONTA-002", "ALFA3", "CUSTODIA",
                "LIMIT", "DIA", LocalDate.of(2026, 10, 18), 10, 10, ONZE, null, null, "ATIVA", null, false, false);
        recusa("limite venda", () -> new NegocioMatching(compra, vendaLimitada, null, null, 1, DEZ)
                .exigirExecutavel(false));
        PernaOco takeProfit = perna("TAKE_PROFIT", "LIMIT", 8, 0, false, "PARCIAL");
        recusa("restante perna", () -> new NegocioMatching(compra, grupo(), null, takeProfit, 3, ONZE)
                .exigirExecutavel(true));
        PernaOco takeProfitAcima = perna("TAKE_PROFIT", "LIMIT", 0, 0, false, "ATIVA");
        recusa("limite venda", () -> new NegocioMatching(compra, grupo(), null, takeProfitAcima, 1, DEZ)
                .exigirExecutavel(true));
    }
}
