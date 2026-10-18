package br.prospero.me.livro;

import br.prospero.me.livro.dominio.ParametrosAtivo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ParametrosAtivoTest {
    @Test
    void tunelComEscalaExtremaERecusadoNaConfiguracao() {
        assertThrows(IllegalArgumentException.class,
                () -> new ParametrosAtivo("ALFA3", new BigDecimal("0.01"), new BigDecimal("10.00"),
                        new BigDecimal("1E-2147483647")));
    }

    @Test
    void referenciaComEscalaExtremaERecusadaAntesDaAritmetica() {
        assertThrows(IllegalArgumentException.class,
                () -> new ParametrosAtivo("ALFA3", new BigDecimal("0.01"),
                        new BigDecimal("1E-2147483647"), BigDecimal.TEN));
    }

    @Test
    void aceitaReferenciaMaximaComZerosETunelPequenoRepresentavel() {
        assertDoesNotThrow(() -> new ParametrosAtivo("ALFA3", new BigDecimal("0.01"),
                new BigDecimal("1000000.00000000"), new BigDecimal("0.00000001")));
        assertDoesNotThrow(() -> new ParametrosAtivo("ALFA3", new BigDecimal("0.01"),
                new BigDecimal("1E+6"), BigDecimal.TEN));
    }
}
