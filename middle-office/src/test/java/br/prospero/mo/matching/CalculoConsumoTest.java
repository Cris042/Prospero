package br.prospero.mo.matching;

import br.prospero.mo.matching.helper.CalculoConsumo;
import br.prospero.mo.mensageria.dominio.MensagemInvalidaException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class CalculoConsumoTest {
    @Test void compraDevolveSomenteMelhoriaDePreco() {
        var consumo=CalculoConsumo.compra(10,3,new BigDecimal("11.00"),new BigDecimal("10.25"),new BigDecimal("110.00"));
        assertEquals(new BigDecimal("33.00"),consumo.retido());
        assertEquals(new BigDecimal("30.75"),consumo.custo());
        assertEquals(new BigDecimal("2.25"),consumo.troco());
        assertEquals(7,consumo.restante());
        assertEquals(consumo.retido(),consumo.custo().add(consumo.troco()));
    }
    @Test void recusaSobreExecucaoPrecoAcimaDoTetoERecursoInsuficiente() {
        assertThrows(MensagemInvalidaException.class,()->CalculoConsumo.compra(2,3,new BigDecimal("11.00"),new BigDecimal("10.00"),new BigDecimal("22.00")));
        assertThrows(MensagemInvalidaException.class,()->CalculoConsumo.compra(2,1,new BigDecimal("11.00"),new BigDecimal("11.01"),new BigDecimal("22.00")));
        assertThrows(MensagemInvalidaException.class,()->CalculoConsumo.compra(2,2,new BigDecimal("11.00"),new BigDecimal("10.00"),new BigDecimal("21.00")));
    }
    @Test void execucaoTotalConsomeTetoSemArredondamento() {
        var consumo=CalculoConsumo.compra(100,100,new BigDecimal("50.05"),new BigDecimal("50.00"),new BigDecimal("5005.00"));
        assertEquals(0,consumo.restante());
        assertEquals(new BigDecimal("5.00"),consumo.troco());
    }
}
