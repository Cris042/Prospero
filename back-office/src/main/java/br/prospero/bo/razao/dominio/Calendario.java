package br.prospero.bo.razao.dominio;

import br.prospero.bo.posicoes.dominio.ProjecaoIncoerenteException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/**
 * Dias úteis seg–sex menos feriados nacionais fixos da tabela versionada (ADR-016). O cálculo parte da
 * {@code dataSessao} do negócio; qualquer dia consultado fora de [inicio, fim] falha fechado, sem supor dia útil.
 */
public record Calendario(LocalDate inicio, LocalDate fim, Set<LocalDate> feriados) {
    private static final int DIAS_UTEIS_ATE_LIQUIDACAO = 2;

    public Calendario {
        if (inicio == null || fim == null || feriados == null || inicio.isAfter(fim)) {
            throw new IllegalArgumentException("Calendário incoerente.");
        }
        feriados = Set.copyOf(feriados);
        if (feriados.stream().anyMatch(d -> d.isBefore(inicio) || d.isAfter(fim))) {
            throw new IllegalArgumentException("Feriado fora da cobertura.");
        }
    }

    public boolean util(LocalDate dia) {
        coberto(dia);
        DayOfWeek semana = dia.getDayOfWeek();
        return semana != DayOfWeek.SATURDAY && semana != DayOfWeek.SUNDAY && !feriados.contains(dia);
    }

    /** D+2: negócio em dia não útil conta a partir do próximo dia útil. */
    public LocalDate liquidacao(LocalDate sessao) {
        LocalDate dia = Objects.requireNonNull(sessao, "sessao");
        while (!util(dia)) dia = dia.plusDays(1);
        for (int contados = 0; contados < DIAS_UTEIS_ATE_LIQUIDACAO; contados++) {
            do dia = dia.plusDays(1); while (!util(dia));
        }
        return dia;
    }

    private void coberto(LocalDate dia) {
        if (dia.isBefore(inicio) || dia.isAfter(fim)) throw new ProjecaoIncoerenteException("CALENDARIO_INSUFICIENTE");
    }
}
