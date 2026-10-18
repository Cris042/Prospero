package br.prospero.fo.ordens;

import br.prospero.fo.comum.dominio.RegraNegocioException;
import br.prospero.fo.ordens.dominio.BaseTunel;
import br.prospero.fo.ordens.dominio.LadoOrdem;
import br.prospero.fo.ordens.dominio.OrigemBase;
import br.prospero.fo.ordens.dominio.ParametrosAtivo;
import br.prospero.fo.ordens.helper.ValidacaoPreco;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValidacaoPrecoTest {
    private static final BigDecimal DEZ_POR_CENTO = new BigDecimal("10");
    private static final BigDecimal CENTAVO = new BigDecimal("0.01");
    private static final ParametrosAtivo LOTE100 = new ParametrosAtivo("OMEGA3", new BigDecimal("0.05"), 100, new BigDecimal("50.00"));

    private static BaseTunel base(String preco) {
        return new BaseTunel(new BigDecimal(preco), OrigemBase.REFERENCIA);
    }

    private static String codigo(Runnable regra) {
        return assertThrows(RegraNegocioException.class, regra::run).codigo();
    }

    @ParameterizedTest(name = "base {0}, tick {1} → [{2}; {3}]")
    @CsvSource({"10.00,0.01,9.00,11.00", "10.05,0.01,9.05,11.05", "10.03,0.05,9.05,11.00",
            "0.01,0.01,0.01,0.01", "0.05,0.01,0.05,0.05", "1000000.00,0.01,900000.00,1100000.00",
            "50.00,0.05,45.00,55.00", "33.33,0.01,30.00,36.66"})
    void limitesInclusivosArredondadosParaDentro(String base, String tick, String minimo, String maximo) {
        ValidacaoPreco.Faixa faixa = ValidacaoPreco.limites(base(base), new BigDecimal(tick), DEZ_POR_CENTO);
        assertEquals(minimo, faixa.minimo().toPlainString());
        assertEquals(maximo, faixa.maximo().toPlainString());
    }

    @ParameterizedTest
    @CsvSource({"9.00,true", "11.00,true", "8.99,false", "11.01,false", "10.00,true"})
    void fronteirasDoTunel(String preco, boolean aceito) {
        Runnable regra = () -> ValidacaoPreco.tunel(new BigDecimal(preco), base("10.00"), CENTAVO, DEZ_POR_CENTO);
        if (aceito) {
            assertDoesNotThrow(regra::run);
        } else {
            RegraNegocioException erro = assertThrows(RegraNegocioException.class, regra::run);
            assertEquals("PRECO_FORA_DO_TUNEL", erro.codigo());
            assertEquals("9.00", erro.detalhes().get("minimo"));
            assertEquals("11.00", erro.detalhes().get("maximo"));
            assertEquals("REFERENCIA", erro.detalhes().get("origemBase"));
        }
    }

    @Test
    void tunelIgnoraPrecoAusenteEAceitaEscalaDiferente() {
        assertDoesNotThrow(() -> ValidacaoPreco.tunel(null, base("10.00"), CENTAVO, DEZ_POR_CENTO));
        assertDoesNotThrow(() -> ValidacaoPreco.tunel(new BigDecimal("9.0"), base("10.00"), CENTAVO, DEZ_POR_CENTO));
    }

    @Test
    void baseUsaUltimoDaSessaoSenaoReferencia() {
        assertEquals(new BaseTunel(new BigDecimal("52.30"), OrigemBase.ULTIMO),
                ValidacaoPreco.base(Optional.of(new BigDecimal("52.30")), LOTE100));
        assertEquals(new BaseTunel(new BigDecimal("50.00"), OrigemBase.REFERENCIA),
                ValidacaoPreco.base(Optional.empty(), LOTE100));
    }

    @Test
    void loteETick() {
        assertDoesNotThrow(() -> ValidacaoPreco.lote(100, LOTE100));
        assertDoesNotThrow(() -> ValidacaoPreco.lote(300, LOTE100));
        assertEquals("QUANTIDADE_FORA_DO_LOTE", codigo(() -> ValidacaoPreco.lote(99, LOTE100)));
        assertEquals("QUANTIDADE_FORA_DO_LOTE", codigo(() -> ValidacaoPreco.lote(150, LOTE100)));
        assertDoesNotThrow(() -> ValidacaoPreco.tick(new BigDecimal("50.05"), LOTE100));
        assertDoesNotThrow(() -> ValidacaoPreco.tick(null, LOTE100));
        assertEquals("PRECO_FORA_DO_TICK", codigo(() -> ValidacaoPreco.tick(new BigDecimal("50.03"), LOTE100)));
    }

    @Test
    void stopLimitCoerenteComOLado() {
        BigDecimal disparo = new BigDecimal("9.50");
        assertDoesNotThrow(() -> ValidacaoPreco.stopLimit(LadoOrdem.VENDA, new BigDecimal("9.45"), disparo));
        assertDoesNotThrow(() -> ValidacaoPreco.stopLimit(LadoOrdem.VENDA, disparo, disparo));
        assertDoesNotThrow(() -> ValidacaoPreco.stopLimit(LadoOrdem.COMPRA, new BigDecimal("9.55"), disparo));
        assertEquals("STOP_LIMIT_INCOERENTE", codigo(() -> ValidacaoPreco.stopLimit(LadoOrdem.VENDA, new BigDecimal("9.51"), disparo)));
        assertEquals("STOP_LIMIT_INCOERENTE", codigo(() -> ValidacaoPreco.stopLimit(LadoOrdem.COMPRA, new BigDecimal("9.49"), disparo)));
    }
}
