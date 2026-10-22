package br.prospero.fo.cotacao;

import br.prospero.fo.cotacao.dominio.SessaoCotacao;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class SessaoCotacaoTest {
    private static SessaoCotacao sessao(long quantidade, String financeiro) {
        return new SessaoCotacao("ALFA3", LocalDate.of(2026, 10, 22), null, quantidade,
                new BigDecimal(financeiro), 1, Instant.EPOCH, false);
    }

    @Test
    void semNegociosVwapAusente() {
        assertNull(sessao(0, "0.00").vwap());
    }

    @Test
    void ponderaPorQuantidadeComQuatroCasas() {
        assertEquals(new BigDecimal("10.0033"), sessao(3, "30.01").vwap());
    }

    @Test
    void desempataMetadeParaParSemArredondarAcumuladores() {
        assertEquals(new BigDecimal("0.0000"), sessao(200, "0.01").vwap());
        assertEquals(new BigDecimal("0.0002"), sessao(200, "0.03").vwap());
        var s = sessao(200, "0.03");
        s.vwap();
        assertEquals(new BigDecimal("0.03"), s.volumeFinanceiro());
    }
}
