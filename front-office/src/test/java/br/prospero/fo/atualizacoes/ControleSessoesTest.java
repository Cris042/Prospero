package br.prospero.fo.atualizacoes;

import br.prospero.fo.atualizacoes.dominio.ControleSessoes;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ControleSessoesTest {
    @Test
    void limitaOitoPorContaETrintaEDuasGlobaisComLiberacaoIdempotente() {
        var controle = new ControleSessoes();
        for (int conta = 1; conta <= 4; conta++) {
            for (int i = 0; i < 8; i++) assertTrue(controle.reservar("CONTA-00" + conta, conta + "-" + i, 100));
            assertFalse(controle.reservar("CONTA-00" + conta, "extra-" + conta, 100));
        }
        controle.liberar("2-0");
        controle.liberar("2-0");
        assertTrue(controle.reservar("CONTA-002", "nova", 100));
        assertFalse(controle.reservar("CONTA-002", "outra", 100));
    }

    @Test
    void idGlobalDuplicadoNaoSubstituiContaNemPrazoEContaDesconhecidaNaoReserva() {
        var controle = new ControleSessoes();
        assertFalse(controle.reservar("CONTA-999", "x", 10));
        assertTrue(controle.reservar("CONTA-002", "x", 10));
        assertFalse(controle.reservar("CONTA-002", "x", 100));
        assertFalse(controle.reservar("CONTA-003", "x", 100));
        assertEquals(Set.of("x"), controle.expurgar(10));
        assertTrue(controle.reservar("CONTA-003", "x", 100));
    }

    @Test
    void confirmaLeaseSemLerRelogioEExpurgaNaFronteiraExata() {
        var controle = new ControleSessoes();
        assertTrue(controle.reservar("CONTA-002", "aberta", 10));
        assertTrue(controle.reservar("CONTA-002", "upgrade-falhou", 10));
        assertTrue(controle.confirmar("aberta", 30));
        assertFalse(controle.confirmar("inexistente", 50));
        assertTrue(controle.expurgar(9).isEmpty());
        assertEquals(Set.of("upgrade-falhou"), controle.expurgar(10));
        assertTrue(controle.expurgar(29).isEmpty());
        assertEquals(Set.of("aberta"), controle.expurgar(30));
        assertTrue(controle.expurgar(30).isEmpty());
    }

    @Test
    void concorrenciaNaoUltrapassaLimiteDaConta() throws Exception {
        var controle = new ControleSessoes();
        var aceitas = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(16)) {
            var tarefas = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 80; i++) {
                String id = "sessao-" + i;
                tarefas.add(executor.submit(() -> { if (controle.reservar("CONTA-002", id, 100)) aceitas.incrementAndGet(); }));
            }
            for (var tarefa : tarefas) tarefa.get(5, TimeUnit.SECONDS);
        }
        assertEquals(8, aceitas.get());
        assertEquals(8, controle.expurgar(100).size());
    }
}
