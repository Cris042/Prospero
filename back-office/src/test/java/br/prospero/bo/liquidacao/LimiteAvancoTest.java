package br.prospero.bo.liquidacao;

import br.prospero.bo.liquidacao.helper.LimiteAvanco;
import br.prospero.bo.liquidacao.dominio.MovimentoInvalidoException;
import br.prospero.bo.razao.dominio.Calendario;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LimiteAvancoTest {
    private static final Calendario CALENDARIO = new Calendario(dia("2026-01-01"), dia("2027-12-31"),
            Set.of(dia("2026-10-12"), dia("2026-11-02"), dia("2026-11-20"), dia("2027-01-01")));

    @Test
    void dataIgualNaoAvancaEDezDiasUteisSaoPermitidos() {
        assertDoesNotThrow(() -> LimiteAvanco.validar(CALENDARIO, dia("2026-10-19"), dia("2026-10-19")));
        assertDoesNotThrow(() -> LimiteAvanco.validar(CALENDARIO, dia("2026-10-19"), dia("2026-11-03")));
    }

    @Test
    void finsDeSemanaEFeriadosNaoConsomemLimiteDeDiasUteis() {
        assertDoesNotThrow(() -> LimiteAvanco.validar(CALENDARIO, dia("2026-10-30"), dia("2026-11-16")));
        assertDoesNotThrow(() -> LimiteAvanco.validar(CALENDARIO, dia("2026-10-19"), dia("2026-10-25")));
    }

    @Test
    void dataRetroativaEOnzeDiasUteisSaoRecusados() {
        var retroativa = assertThrows(MovimentoInvalidoException.class,
                () -> LimiteAvanco.validar(CALENDARIO, dia("2026-10-19"), dia("2026-10-18")));
        assertEquals("DATA_RETROATIVA", retroativa.getMessage());
        assertThrows(MovimentoInvalidoException.class,
                () -> LimiteAvanco.validar(CALENDARIO, dia("2026-10-19"), dia("2026-11-04")));
    }

    @Test
    void foraDoCalendarioFalhaFechadoMesmoSeAvancoForCurto() {
        var erro = assertThrows(MovimentoInvalidoException.class,
                () -> LimiteAvanco.validar(CALENDARIO, dia("2027-12-31"), dia("2028-01-01")));
        assertEquals(422, erro.status());
        assertEquals("CALENDARIO_INSUFICIENTE", erro.codigo());
    }

    private static LocalDate dia(String texto) { return LocalDate.parse(texto); }
}
