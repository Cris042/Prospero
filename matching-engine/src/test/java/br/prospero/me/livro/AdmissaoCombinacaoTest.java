package br.prospero.me.livro;

import br.prospero.me.livro.dominio.Admissao;
import br.prospero.me.livro.dominio.Lado;
import br.prospero.me.livro.dominio.TipoOrdem;
import br.prospero.me.livro.dominio.Validade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Caracterização da fonte única de combinação (refatoração sem mudança de comportamento): compara
 * {@link Admissao#combinacaoValida()} e {@link Admissao#limiteAcimaDaReserva()} com as expressões que o
 * MotorMatching.recusa usava antes da extração, em toda a matriz tipo × validade × lado × presença de preços.
 */
class AdmissaoCombinacaoTest {
    private static final LocalDate SESSAO = LocalDate.of(2026, 10, 19);
    private static final BigDecimal[] PRECOS = {null, new BigDecimal("9.00"), new BigDecimal("10.00"),
        new BigDecimal("11.00")};

    @Test
    void matrizIgualAExpressaoOriginalDoMotor() {
        int casos = 0;
        for (TipoOrdem tipo : TipoOrdem.values()) {
            for (Validade validade : Validade.values()) {
                for (Lado lado : Lado.values()) {
                    for (BigDecimal limite : PRECOS) {
                        for (BigDecimal maximo : PRECOS) {
                            for (BigDecimal gatilho : PRECOS) {
                                Admissao admissao = admissao(lado, tipo, validade, limite, maximo, gatilho);
                                boolean valida = combinacaoOriginal(admissao);
                                assertEquals(valida, admissao.combinacaoValida(), admissao::toString);
                                if (valida) {
                                    assertEquals(acimaDaReservaOriginal(admissao), admissao.limiteAcimaDaReserva(),
                                            admissao::toString);
                                }
                                casos++;
                            }
                        }
                    }
                }
            }
        }
        assertEquals(TipoOrdem.values().length * Validade.values().length * 2 * 64, casos);
    }

    @Test
    void alvoDoTakeProfitPrecisaFicarAlemDoGatilhoConformeOLado() {
        BigDecimal nove = new BigDecimal("9.00");
        BigDecimal dez = new BigDecimal("10.00");
        Admissao stopVenda = admissao(Lado.VENDA, TipoOrdem.STOP, Validade.DIA, null, null, dez);
        assertTrue(admissao(Lado.VENDA, TipoOrdem.LIMIT, Validade.DIA, new BigDecimal("10.01"), null, null)
                .alvoAlemDoGatilho(stopVenda));
        assertFalse(admissao(Lado.VENDA, TipoOrdem.LIMIT, Validade.DIA, dez, null, null).alvoAlemDoGatilho(stopVenda));
        Admissao stopCompra = admissao(Lado.COMPRA, TipoOrdem.STOP, Validade.DIA, null, dez, dez);
        assertTrue(admissao(Lado.COMPRA, TipoOrdem.LIMIT, Validade.DIA, nove, dez, null).alvoAlemDoGatilho(stopCompra));
        assertFalse(admissao(Lado.COMPRA, TipoOrdem.LIMIT, Validade.DIA, dez, dez, null).alvoAlemDoGatilho(stopCompra));
        assertFalse(admissao(Lado.COMPRA, TipoOrdem.MARKET, Validade.IOC, null, dez, null).alvoAlemDoGatilho(stopCompra));
    }

    @Test
    void tiposAgrupamStopsELimites() {
        assertEquals("[STOP, STOP_LIMIT]", Arrays.toString(Arrays.stream(TipoOrdem.values())
                .filter(TipoOrdem::stop).toArray()));
        assertEquals("[LIMIT, STOP_LIMIT]", Arrays.toString(Arrays.stream(TipoOrdem.values())
                .filter(TipoOrdem::comLimite).toArray()));
    }

    /** Cópia literal das guardas de combinação do MotorMatching.recusa antes da refatoração. */
    private static boolean combinacaoOriginal(Admissao a) {
        boolean market = a.tipo() == TipoOrdem.MARKET;
        boolean stop = a.tipo() == TipoOrdem.STOP || a.tipo() == TipoOrdem.STOP_LIMIT;
        boolean limit = a.tipo() == TipoOrdem.LIMIT || a.tipo() == TipoOrdem.STOP_LIMIT;
        boolean compra = a.lado() == Lado.COMPRA;
        if ((market && a.validade() == Validade.DIA) || (stop && a.validade() != Validade.DIA)
                || limit != (a.precoLimite() != null) || stop != (a.precoGatilho() != null)
                || compra != (a.precoMaximo() != null)) {
            return false;
        }
        return !(a.tipo() == TipoOrdem.STOP_LIMIT && (compra
                ? a.precoLimite().compareTo(a.precoGatilho()) < 0
                : a.precoLimite().compareTo(a.precoGatilho()) > 0));
    }

    private static boolean acimaDaReservaOriginal(Admissao a) {
        boolean limit = a.tipo() == TipoOrdem.LIMIT || a.tipo() == TipoOrdem.STOP_LIMIT;
        return a.lado() == Lado.COMPRA && limit && a.precoLimite().compareTo(a.precoMaximo()) > 0;
    }

    private static Admissao admissao(Lado lado, TipoOrdem tipo, Validade validade, BigDecimal limite,
                                     BigDecimal maximo, BigDecimal gatilho) {
        return new Admissao(UUID.randomUUID(), UUID.randomUUID(), "CONTA-001", "ALFA3", lado, tipo, validade, 100,
                limite, maximo, gatilho, SESSAO);
    }
}
