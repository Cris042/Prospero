package br.prospero.bo.posicoes;

import br.prospero.bo.posicoes.dominio.Racional;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class RacionalTest {
    @Test
    void normalizaSinalMdcEZero() {
        conferir(2, 3, fracao(-10, -15));
        conferir(-2, 3, fracao(10, -15));
        assertEquals(BigInteger.ONE, fracao(0, -99).denominador());
        assertEquals(Racional.ZERO, fracao(0, -99));
        assertThrows(IllegalArgumentException.class, () -> fracao(1, 0));
    }

    @Test
    void operaFracoesPeriodicasSemArredondamento() {
        Racional terco = fracao(1, 3);
        conferir(1, 1, terco.multiplicar(3));
        conferir(5, 6, terco.somar(fracao(1, 2)));
        conferir(-1, 6, terco.subtrair(fracao(1, 2)));
        conferir(-2, 15, terco.multiplicar(fracao(-2, 5)));
        conferir(-1, 9, terco.dividir(-3));
        assertThrows(IllegalArgumentException.class, () -> terco.dividir(0));
    }

    @Test
    void converteDecimalExatoInclusiveEscalaNegativa() {
        conferir(2469, 20, Racional.de(new BigDecimal("123.4500")));
        conferir(1000, 1, Racional.de(new BigDecimal("1E+3")));
        conferir(-1, 100000, Racional.de(new BigDecimal("-0.00001")));
    }

    @Test
    void arredondaApenasNaExposicaoComQuatroCasasHalfEven() {
        assertEquals("0.3333", fracao(1, 3).formatar());
        assertEquals("0.6667", fracao(2, 3).formatar());
        assertEquals("1.2344", Racional.de(new BigDecimal("1.23445")).formatar());
        assertEquals("1.2346", Racional.de(new BigDecimal("1.23455")).formatar());
        assertEquals("-1.2344", Racional.de(new BigDecimal("-1.23445")).formatar());
        assertEquals("-1.2346", Racional.de(new BigDecimal("-1.23455")).formatar());
        assertEquals("0.0000", Racional.ZERO.formatar());
    }

    @Test
    void impedeRepresentacaoIrredutivelAcimaDoOrcamentoSemTruncar() {
        BigInteger excesso = BigInteger.ONE.shiftLeft(131072);
        assertThrows(IllegalStateException.class, () -> Racional.fracao(excesso, BigInteger.ONE));
        assertThrows(IllegalStateException.class, () -> Racional.fracao(BigInteger.ONE, excesso));
        conferir(1, 1, Racional.fracao(excesso, excesso));
    }

    @Test
    void impedeExpoenteDecimalExtremoAntesDeAlocarPotenciaGigante() {
        assertThrows(IllegalStateException.class, () -> Racional.de(new BigDecimal("1E+1000000")));
        assertThrows(IllegalStateException.class, () -> Racional.de(new BigDecimal("1E-1000000")));
        assertThrows(IllegalStateException.class,
                () -> Racional.de(new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE)));
    }

    @Test
    void excessoNaOperacaoNaoAlteraOperandoNemProduzAproximacao() {
        BigInteger numerador = BigInteger.ONE.shiftLeft(65536).add(BigInteger.ONE);
        Racional grande = Racional.fracao(numerador, BigInteger.ONE);
        assertThrows(IllegalStateException.class, () -> grande.multiplicar(grande));
        assertEquals(numerador, grande.numerador());
        assertEquals(BigInteger.ONE, grande.denominador());
    }

    private static Racional fracao(long numerador, long denominador) {
        return Racional.fracao(BigInteger.valueOf(numerador), BigInteger.valueOf(denominador));
    }

    private static void conferir(long numerador, long denominador, Racional valor) {
        assertEquals(BigInteger.valueOf(numerador), valor.numerador());
        assertEquals(BigInteger.valueOf(denominador), valor.denominador());
    }
}
