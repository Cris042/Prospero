package br.prospero.mo.decimal;

import br.prospero.mo.decimal.dominio.RegraDeSinal;
import br.prospero.mo.decimal.dominio.ValorEscalado;
import br.prospero.mo.decimal.helper.ConversaoDecimalExata;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DecimalExatoTest {
    @Test
    void preservaUnidadesEEscalaInclusiveExtremos() {
        for (long unidades : new long[] {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE}) {
            for (int escala : new int[] {0, 1, 18}) {
                ValorEscalado valor = new ValorEscalado(unidades, escala);
                assertEquals(BigDecimal.valueOf(unidades, escala), valor.emDecimal());
                assertEquals(escala, valor.emDecimal().scale());
            }
        }
    }

    @Test
    void construtorRejeitaEscalaForaDoContrato() {
        for (int escala : new int[] {Integer.MIN_VALUE, -1, 19, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new ValorEscalado(0, escala));
        }
    }

    @Test
    void igualdadeRepresentacionalIncluiEscalaEHashConsistente() {
        ValorEscalado primeiro = new ValorEscalado(100, 2);
        ValorEscalado igual = new ValorEscalado(100, 2);
        assertEquals(primeiro, igual);
        assertEquals(primeiro.hashCode(), igual.hashCode());
        assertNotEquals(primeiro, new ValorEscalado(10, 1));
        assertNotEquals(primeiro, new ValorEscalado(101, 2));
    }

    @Test
    void converteDecimalExatoComZerosFinaisSemArredondar() {
        assertEquals(new ValorEscalado(123, 2), converter("1.2300", 2));
        assertEquals(new ValorEscalado(1230, 3), converter("1.23", 3));
        assertEquals(new ValorEscalado(-123, 2), converter("-1.2300", 2));
        assertEquals(new ValorEscalado(0, 18), converter("0.000", 18));
    }

    @Test
    void aceitaNotacaoCientificaDentroDosLimites() {
        assertEquals(new ValorEscalado(123000, 0), converter("1.23E+5", 0));
        assertEquals(new ValorEscalado(1, 18), converter("1E-18", 18));
        assertEquals(new ValorEscalado(1000000000000000000L, 0), converter("1E+18", 0));
    }

    @Test
    void aceitaLongComDezoitoZerosFinaisRemovidosExatamente() {
        assertEquals(new ValorEscalado(Long.MAX_VALUE, 0),
                converter("9223372036854775807.000000000000000000", 0));
        assertEquals(new ValorEscalado(Long.MIN_VALUE, 0),
                converter("-9223372036854775808.000000000000000000", 0));
    }

    @Test
    void aceitaExtremosDoLongAposEscalonamento() {
        assertEquals(new ValorEscalado(Long.MAX_VALUE, 2), converter("92233720368547758.07", 2));
        assertEquals(new ValorEscalado(Long.MIN_VALUE, 2), converter("-92233720368547758.08", 2));
    }

    @Test
    void rejeitaOverflowNosDoisSentidos() {
        rejeitar("9223372036854775808", 0, RegraDeSinal.QUALQUER);
        rejeitar("-9223372036854775809", 0, RegraDeSinal.QUALQUER);
        rejeitar("92233720368547758.08", 2, RegraDeSinal.QUALQUER);
        rejeitar("-92233720368547758.09", 2, RegraDeSinal.QUALQUER);
    }

    @Test
    void rejeitaFracaoNaoRepresentavelMesmoJuntoDeZero() {
        rejeitar("1.231", 2, RegraDeSinal.QUALQUER);
        rejeitar("-1.231", 2, RegraDeSinal.QUALQUER);
        rejeitar("0.001", 2, RegraDeSinal.QUALQUER);
    }

    @Test
    void aplicaCadaPoliticaDeSinalSemAlterarTipoGenerico() {
        for (RegraDeSinal regra : RegraDeSinal.values()) {
            assertEquals(new ValorEscalado(123, 2),
                    ConversaoDecimalExata.converter(new BigDecimal("1.23"), 2, regra));
        }
        assertEquals(new ValorEscalado(-123, 2), converter("-1.23", 2));
        assertEquals(new ValorEscalado(0, 2), converter("-0.00", 2));
        assertEquals(new ValorEscalado(0, 2),
                ConversaoDecimalExata.converter(BigDecimal.ZERO, 2, RegraDeSinal.NAO_NEGATIVO));
        rejeitar("-1.23", 2, RegraDeSinal.NAO_NEGATIVO);
        rejeitar("-1.23", 2, RegraDeSinal.POSITIVO);
        rejeitar("0.00", 2, RegraDeSinal.POSITIVO);
    }

    @Test
    void rejeitaEntradasAusentesComExcecaoDoContrato() {
        assertThrows(IllegalArgumentException.class,
                () -> ConversaoDecimalExata.converter(null, 2, RegraDeSinal.QUALQUER));
        assertThrows(IllegalArgumentException.class,
                () -> ConversaoDecimalExata.converter(BigDecimal.ONE, 2, null));
    }

    @Test
    void conversorRejeitaEscalaDestinoInvalidaInclusiveParaZero() {
        for (int escala : new int[] {Integer.MIN_VALUE, -1, 19, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ConversaoDecimalExata.converter(BigDecimal.ZERO, escala, RegraDeSinal.QUALQUER));
        }
    }

    @Test
    void rejeitaEscalaOriginalForaDoLimiteAntesDeExpandirInclusiveZero() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int escala : new int[] {Integer.MIN_VALUE, -19, 19, Integer.MAX_VALUE}) {
                for (BigInteger unidades : new BigInteger[] {BigInteger.ZERO, BigInteger.ONE}) {
                    assertThrows(IllegalArgumentException.class, () -> ConversaoDecimalExata.converter(
                            new BigDecimal(unidades, escala), 0, RegraDeSinal.QUALQUER));
                }
            }
        });
    }

    @Test
    void rejeitaPrecisaoExcessivaComEscalaOriginalValida() {
        assertThrows(IllegalArgumentException.class, () -> ConversaoDecimalExata.converter(
                new BigDecimal(BigInteger.TEN.pow(38), 18), 0, RegraDeSinal.QUALQUER));
        BigDecimal enorme = new BigDecimal(BigInteger.TEN.pow(10000), 18);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(IllegalArgumentException.class,
                () -> ConversaoDecimalExata.converter(enorme, 0, RegraDeSinal.QUALQUER)));
    }

    @Test
    void diagnosticoInformaCategoriaSemReproduzirValorRecebido() {
        IllegalArgumentException erro = rejeitar("123456789.123456789", 2, RegraDeSinal.QUALQUER);
        assertNotNull(erro.getMessage());
        assertFalse(erro.getMessage().isBlank());
        assertFalse(erro.getMessage().contains("123456789.123456789"));
        assertFalse(erro.getMessage().contains("123456789"));
    }

    @Test
    void diagnosticosDistinguemCategoriasDeFalha() {
        List<String> mensagens = List.of(
                rejeitar("1.001", 2, RegraDeSinal.QUALQUER).getMessage(),
                rejeitar("9223372036854775808", 0, RegraDeSinal.QUALQUER).getMessage(),
                rejeitar("-1", 0, RegraDeSinal.POSITIVO).getMessage(),
                rejeitar("1E-19", 0, RegraDeSinal.QUALQUER).getMessage());
        assertEquals(mensagens.size(), new HashSet<>(mensagens).size());
    }

    private static ValorEscalado converter(String valor, int escala) {
        return ConversaoDecimalExata.converter(new BigDecimal(valor), escala, RegraDeSinal.QUALQUER);
    }

    private static IllegalArgumentException rejeitar(String valor, int escala, RegraDeSinal regra) {
        return assertThrows(IllegalArgumentException.class,
                () -> ConversaoDecimalExata.converter(new BigDecimal(valor), escala, regra));
    }
}
