package br.prospero.bo.liquidacao.kafka;

import br.prospero.bo.liquidacao.jpa.LiquidacaoJpa;
import br.prospero.bo.posicoes.dominio.ProjecaoIncoerenteException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkerLiquidacaoTest {
    @Test
    void falhaAoPersistirQuarentenaNaoEscapaAgendaENaoRepeteEfeitoFinanceiro() {
        var repositorio = new Repositorio();
        var worker = new WorkerLiquidacao();
        worker.liquidacao = repositorio;
        worker.situacao = () -> true;
        assertDoesNotThrow(worker::ciclo);
        assertEquals(1, repositorio.lotes);
        assertEquals(1, repositorio.marcacoes);
        assertDoesNotThrow(worker::ciclo);
        assertEquals(1, repositorio.lotes, "nova tentativa é só do marcador, nunca do lote incoerente");
        assertEquals(2, repositorio.marcacoes);
    }

    private static class Repositorio extends LiquidacaoJpa {
        private int lotes;
        private int marcacoes;
        Repositorio() { super(null, null); }
        @Override public int processarLote() { lotes++; throw new ProjecaoIncoerenteException("INVARIANTE"); }
        @Override public void quarentena() {
            if (++marcacoes == 1) throw new IllegalStateException("banco temporariamente indisponível");
        }
    }
}
