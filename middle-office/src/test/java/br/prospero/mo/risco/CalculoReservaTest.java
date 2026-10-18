package br.prospero.mo.risco;

import br.prospero.mo.risco.dominio.ComandoRisco;
import br.prospero.mo.risco.dominio.Exigencia;
import br.prospero.mo.risco.dominio.Lado;
import br.prospero.mo.risco.dominio.MotivoRejeicao;
import br.prospero.mo.risco.dominio.ParametrosRisco;
import br.prospero.mo.risco.dominio.Perna;
import br.prospero.mo.risco.dominio.TipoOrdem;
import br.prospero.mo.risco.helper.CalculoReserva;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reserva por tipo (ADR-012): valores exatos em BigDecimal, teto do túnel arredondado ao tick para dentro. */
class CalculoReservaTest {
    static final ParametrosRisco ALFA = new ParametrosRisco("ALFA3", 1, new BigDecimal("0.01"), BigDecimal.TEN, 100_000, 1);
    static final ParametrosRisco OMEGA = new ParametrosRisco("OMEGA3", 100, new BigDecimal("0.05"), BigDecimal.TEN,
            100_000, 1);

    static Perna perna(TipoOrdem tipo, long quantidade, String limite, String gatilho) {
        return new Perna(UUID.randomUUID(), tipo, quantidade, limite == null ? null : new BigDecimal(limite),
                gatilho == null ? null : new BigDecimal(gatilho));
    }

    static ComandoRisco comando(Lado lado, String base, Perna... pernas) {
        return new ComandoRisco(UUID.randomUUID(), UUID.randomUUID(), "CONTA-002", "ALFA3", lado,
                base == null ? null : new BigDecimal(base), List.of(pernas), null);
    }

    static Exigencia exigir(ComandoRisco c) {
        return CalculoReserva.exigencia(c, ALFA);
    }

    @Test
    void limitEStopLimitReservamPeloLimite() {
        assertEquals(new Exigencia.Caixa(new BigDecimal("1010.00")),
                exigir(comando(Lado.COMPRA, "10.00", perna(TipoOrdem.LIMIT, 100, "10.10", null))));
        assertEquals(new Exigencia.Caixa(new BigDecimal("1050.00")),
                exigir(comando(Lado.COMPRA, "10.00", perna(TipoOrdem.STOP_LIMIT, 100, "10.50", "10.40"))));
    }

    @Test
    void marketEStopReservamPeloTetoDoTunel() {
        // 10,00 × 1,10 = 11,00; 10,07 × 1,10 = 11,077 → 11,07 (para dentro do túnel).
        assertEquals(new Exigencia.Caixa(new BigDecimal("1100.00")),
                exigir(comando(Lado.COMPRA, "10.00", perna(TipoOrdem.MARKET, 100, null, null))));
        assertEquals(new Exigencia.Caixa(new BigDecimal("1107.00")),
                exigir(comando(Lado.COMPRA, "10.00", perna(TipoOrdem.STOP, 100, null, "10.07"))));
        assertEquals(new BigDecimal("55.00"), CalculoReserva.teto(new BigDecimal("50.00"), OMEGA));
        assertEquals(new BigDecimal("55.15"), CalculoReserva.teto(new BigDecimal("50.15"), OMEGA), "55,165 → 55,15");
        assertEquals(new BigDecimal("55.20"), CalculoReserva.teto(new BigDecimal("50.20"), OMEGA), "55,22 → 55,20");
    }

    @Test
    void marketSemBaseNuncaAprova() {
        assertEquals(new Exigencia.Recusa(MotivoRejeicao.PRECO_BASE_AUSENTE),
                exigir(comando(Lado.COMPRA, null, perna(TipoOrdem.MARKET, 1, null, null))));
    }

    @Test
    void vendaReservaCustodiaSemShort() {
        assertEquals(new Exigencia.Custodia(300),
                exigir(comando(Lado.VENDA, "10.00", perna(TipoOrdem.LIMIT, 300, "9.90", null))));
        assertEquals(new Exigencia.Custodia(5), exigir(comando(Lado.VENDA, "10.00",
                perna(TipoOrdem.LIMIT, 5, "10.50", null), perna(TipoOrdem.STOP, 5, null, "9.50"))),
                "OCO de venda conta a custódia uma vez");
    }

    @Test
    void ocoDeCompraReservaOMaiorCustoEntreAsPernas() {
        // Cobertura: alvo 9,50 × 10 = 95,00; stop 10,50 → teto 11,55 × 10 = 115,50.
        assertEquals(new Exigencia.Caixa(new BigDecimal("115.50")), exigir(comando(Lado.COMPRA, "10.00",
                perna(TipoOrdem.LIMIT, 10, "9.50", null), perna(TipoOrdem.STOP, 10, null, "10.50"))));
    }

    @Test
    void quantidadeMaximaInclusivaELote() {
        assertEquals(new Exigencia.Caixa(new BigDecimal("1000000.00")),
                exigir(comando(Lado.COMPRA, "10.00", perna(TipoOrdem.LIMIT, 100_000, "10.00", null))));
        assertEquals(new Exigencia.Recusa(MotivoRejeicao.QUANTIDADE_MAXIMA_EXCEDIDA),
                exigir(comando(Lado.COMPRA, "10.00", perna(TipoOrdem.LIMIT, 100_001, "10.00", null))));
        assertEquals(new Exigencia.Recusa(MotivoRejeicao.QUANTIDADE_FORA_DO_LOTE), CalculoReserva.exigencia(
                comando(Lado.COMPRA, "50.00", perna(TipoOrdem.LIMIT, 150, "50.00", null)), OMEGA));
    }

    @Test
    void valorMaximoDoContratoCabeSemArredondar() {
        ParametrosRisco semTeto = new ParametrosRisco("ALFA3", 1, new BigDecimal("0.01"), BigDecimal.TEN, 1_000_000, 1);
        assertEquals(new Exigencia.Caixa(new BigDecimal("1100000000000.00")), CalculoReserva.exigencia(
                comando(Lado.COMPRA, "1000000.00", perna(TipoOrdem.MARKET, 1_000_000, null, null)), semTeto));
    }
}
