package br.prospero.bo.posicoes;

import br.prospero.bo.posicoes.dominio.Posicao;
import br.prospero.bo.posicoes.dominio.Racional;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PosicaoTest {
    @Test
    void iniciaSemPosicaoSemInventarCustoDeAbertura() {
        Posicao vazia = Posicao.vazia();
        assertEquals(0, vazia.quantidade());
        assertNull(vazia.medio());
        assertEquals(Racional.ZERO, vazia.realizado());
        assertNull(vazia.naoRealizado(null));
        assertEquals(Racional.ZERO, vazia.naoRealizado(preco("100.00")));
    }

    @Test
    void ponderaAmpliacoesEPreservaMedioNasReducoes() {
        Posicao posicao = Posicao.vazia().aplicar(10, preco("100.00")).aplicar(10, preco("110.00"));
        assertEquals(20, posicao.quantidade());
        assertEquals("105.0000", posicao.medio().formatar());
        assertEquals(Racional.ZERO, posicao.realizado());
        Posicao reduzida = posicao.aplicar(-4, preco("120.00"));
        assertEquals(16, reduzida.quantidade());
        assertEquals(posicao.medio(), reduzida.medio());
        assertEquals("60.0000", reduzida.realizado().formatar());
    }

    @Test
    void reduzLongZeraEPreservaRealizadoAcumulado() {
        Posicao reduzida = Posicao.vazia().aplicar(10, preco("100.00")).aplicar(-4, preco("110.00"));
        assertEquals(6, reduzida.quantidade());
        assertEquals("100.0000", reduzida.medio().formatar());
        assertEquals("40.0000", reduzida.realizado().formatar());
        assertEquals("30.0000", reduzida.naoRealizado(preco("105.00")).formatar());
        assertNull(reduzida.naoRealizado(null));
        Posicao zerada = reduzida.aplicar(-6, preco("90.00"));
        assertEquals(0, zerada.quantidade());
        assertNull(zerada.medio());
        assertEquals("-20.0000", zerada.realizado().formatar());
        Posicao reaberta = zerada.aplicar(2, preco("80.00"));
        assertEquals("80.0000", reaberta.medio().formatar());
        assertEquals(zerada.realizado(), reaberta.realizado());
    }

    @Test
    void reduzShortEZeraComSinalCorreto() {
        Posicao reduzida = Posicao.vazia().aplicar(-10, preco("100.00")).aplicar(4, preco("90.00"));
        assertEquals(-6, reduzida.quantidade());
        assertEquals("100.0000", reduzida.medio().formatar());
        assertEquals("40.0000", reduzida.realizado().formatar());
        assertEquals("30.0000", reduzida.naoRealizado(preco("95.00")).formatar());
        Posicao zerada = reduzida.aplicar(6, preco("110.00"));
        assertEquals(0, zerada.quantidade());
        assertNull(zerada.medio());
        assertEquals("-20.0000", zerada.realizado().formatar());
    }

    @Test
    void viradasNosDoisSentidosAbremSaldoAoPrecoExecutado() {
        Posicao vendida = Posicao.vazia().aplicar(10, preco("100.00")).aplicar(-15, preco("110.00"));
        assertEquals(-5, vendida.quantidade());
        assertEquals("110.0000", vendida.medio().formatar());
        assertEquals("100.0000", vendida.realizado().formatar());
        Posicao comprada = vendida.aplicar(8, preco("100.00"));
        assertEquals(3, comprada.quantidade());
        assertEquals("100.0000", comprada.medio().formatar());
        assertEquals("150.0000", comprada.realizado().formatar());
    }

    @Test
    void conservaFracaoNaoTerminanteAposReducaoEAmpliacao() {
        Posicao inicial = Posicao.vazia().aplicar(2, preco("10.00")).aplicar(1, preco("11.00"));
        assertEquals(fracao(31, 3), inicial.medio());
        Posicao reduzida = inicial.aplicar(-1, preco("12.00"));
        assertEquals(fracao(31, 3), reduzida.medio());
        assertEquals(fracao(5, 3), reduzida.realizado());
        Posicao ampliada = reduzida.aplicar(1, preco("10.00"));
        assertEquals(fracao(92, 9), ampliada.medio());
        assertEquals(fracao(5, 3), ampliada.realizado());
        assertEquals(fracao(7, 3), ampliada.naoRealizado(preco("11.00")));
    }

    @Test
    void exposicaoNaoArredondaAcumuladoresDoDominio() {
        Posicao posicao = Posicao.vazia().aplicar(2, preco("10.00")).aplicar(1, preco("11.00"));
        assertEquals("10.3333", posicao.medio().formatar());
        assertEquals(fracao(31, 3), posicao.medio());
        assertEquals(fracao(2, 1), posicao.naoRealizado(preco("11.00")));
    }

    @Test
    void rejeitaEntradasInvalidasSemArredondarPrecoOuAlterarEstado() {
        Posicao posicao = Posicao.vazia().aplicar(1, preco("10.00"));
        assertThrows(IllegalArgumentException.class, () -> posicao.aplicar(0, preco("10.00")));
        assertThrows(IllegalArgumentException.class, () -> posicao.aplicar(1, null));
        assertThrows(IllegalArgumentException.class, () -> posicao.aplicar(1, preco("0.00")));
        assertThrows(IllegalArgumentException.class, () -> posicao.aplicar(1, preco("-0.01")));
        assertThrows(IllegalArgumentException.class, () -> posicao.aplicar(1, preco("1.001")));
        assertThrows(IllegalArgumentException.class, () -> posicao.aplicar(1, preco("1000000.01")));
        assertEquals(1, posicao.quantidade());
        assertEquals("10.0000", posicao.medio().formatar());
    }

    @Test
    void quantidadeNaoEstouraSilenciosamente() {
        Posicao maxima = new Posicao(Long.MAX_VALUE, Racional.de(preco("1.00")), Racional.ZERO);
        assertThrows(ArithmeticException.class, () -> maxima.aplicar(1, preco("1.00")));
    }

    @Test
    void quantidadeMinimaNaoPassaPorAbsComOverflow() {
        assertThrows(IllegalArgumentException.class, () -> Posicao.vazia().aplicar(Long.MIN_VALUE, preco("1.00")));
        assertThrows(IllegalArgumentException.class,
                () -> new Posicao(Long.MIN_VALUE, Racional.de(preco("1.00")), Racional.ZERO));
        Posicao menor = new Posicao(-Long.MAX_VALUE, Racional.de(preco("1.00")), Racional.ZERO);
        assertThrows(ArithmeticException.class, () -> menor.aplicar(-1, preco("1.00")));
    }

    @Test
    void reconstruirPosicaoExigeMedioCoerenteERealizadoPresente() {
        Racional um = Racional.de(preco("1.00"));
        assertThrows(IllegalArgumentException.class, () -> new Posicao(0, um, Racional.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new Posicao(1, null, Racional.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new Posicao(1, Racional.ZERO, Racional.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new Posicao(1, um, null));
    }

    @Test
    void patrimonioConservaFluxoDeCaixaEmSequenciasDeterministicas() {
        for (int semente = 0; semente < 100; semente++) {
            Random aleatorio = new Random(semente);
            Posicao posicao = Posicao.vazia();
            BigDecimal caixa = BigDecimal.ZERO;
            long quantidadeEsperada = 0;
            for (int passo = 0; passo < 100; passo++) {
                long delta = (aleatorio.nextBoolean() ? 1 : -1) * (1 + aleatorio.nextInt(9));
                BigDecimal executado = BigDecimal.valueOf(500 + aleatorio.nextInt(1501), 2);
                posicao = posicao.aplicar(delta, executado);
                quantidadeEsperada += delta;
                caixa = caixa.subtract(executado.multiply(BigDecimal.valueOf(delta)));
                BigDecimal marcacao = BigDecimal.valueOf(500 + aleatorio.nextInt(1501), 2);
                BigDecimal patrimonio = caixa.add(marcacao.multiply(BigDecimal.valueOf(quantidadeEsperada)));
                Racional resultado = posicao.realizado().somar(posicao.naoRealizado(marcacao));
                assertEquals(quantidadeEsperada, posicao.quantidade(), "quantidade: " + semente + "/" + passo);
                assertEquals(patrimonio.unscaledValue().multiply(resultado.denominador()),
                        resultado.numerador().multiply(BigInteger.TEN.pow(patrimonio.scale())),
                        "conservação: " + semente + "/" + passo);
            }
        }
    }

    private static BigDecimal preco(String valor) { return new BigDecimal(valor); }

    private static Racional fracao(long numerador, long denominador) {
        return Racional.fracao(BigInteger.valueOf(numerador), BigInteger.valueOf(denominador));
    }
}
