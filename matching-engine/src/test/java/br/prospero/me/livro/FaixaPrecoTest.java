package br.prospero.me.livro;

import br.prospero.me.livro.helper.FaixaPreco;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Oráculo em centavos, independente do cálculo BigDecimal usado pelo motor e pelo modelo de propriedades. */
class FaixaPrecoTest {
    @Test
    void faixaDeDezPorCentoArredondaParaDentroEmCadaTick() {
        for (long tickCentavos : new long[]{1, 5, 25, 100}) {
            BigDecimal tick = BigDecimal.valueOf(tickCentavos, 2);
            for (long baseCentavos = tickCentavos; baseCentavos <= 20_000; baseCentavos += tickCentavos) {
                BigDecimal base = BigDecimal.valueOf(baseCentavos, 2);
                long divisor = 100 * tickCentavos;
                long teto = baseCentavos * 110 / divisor * tickCentavos;
                long piso = (baseCentavos * 90 + divisor - 1) / divisor * tickCentavos;
                String contexto = "base=" + base + ", tick=" + tick;
                assertEquals(BigDecimal.valueOf(teto, 2), FaixaPreco.teto(base, tick, BigDecimal.TEN), contexto);
                assertEquals(BigDecimal.valueOf(piso, 2), FaixaPreco.piso(base, tick, BigDecimal.TEN), contexto);
            }
        }
    }
}
