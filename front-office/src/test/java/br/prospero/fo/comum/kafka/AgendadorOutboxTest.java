package br.prospero.fo.comum.kafka;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Tempo virtual: nenhum teste usa sleep ou depende do escalonador do host. */
class AgendadorOutboxTest {
    @Test void fallbackDescobreBacklogSemSinalECadenciaOciosaPermanece200ms() {
        var tempo = new Tempo(); var chamadas = new AtomicInteger();
        var alvo = new AgendadorOutbox(tempo, () -> tempo.agora, () -> {
            chamadas.incrementAndGet(); return AgendadorOutbox.Resultado.ESGOTADO;
        }, () -> {});
        alvo.iniciar(); tempo.avancar(0); assertEquals(0, chamadas.get());
        tempo.avancar(1); assertEquals(1, chamadas.get());
        tempo.avancar(199); assertEquals(1, chamadas.get());
        tempo.avancar(1); assertEquals(2, chamadas.get());
        alvo.close();
    }

    @Test void rajadaCoalesceSemPublicarInline() {
        var tempo = new Tempo(); var chamadas = new AtomicInteger();
        var alvo = new AgendadorOutbox(tempo, () -> tempo.agora, () -> {
            chamadas.incrementAndGet(); return AgendadorOutbox.Resultado.ESGOTADO;
        }, () -> {});
        alvo.iniciar(); tempo.avancar(1); chamadas.set(0);
        for (int i = 0; i < 1000; i++) alvo.solicitar();
        assertEquals(0, chamadas.get());
        assertTrue(tempo.pendentes() <= 2, "fallback e no máximo uma urgência");
        tempo.avancar(0); assertEquals(1, chamadas.get()); alvo.close();
    }

    @Test void sinalDurantePublicacaoReservaUmaExecucaoPosterior() {
        var tempo = new Tempo(); var chamadas = new AtomicInteger();
        AgendadorOutbox[] referencia = new AgendadorOutbox[1];
        referencia[0] = new AgendadorOutbox(tempo, () -> tempo.agora, () -> {
            if (chamadas.incrementAndGet() == 1) {
                for (int i = 0; i < 50; i++) referencia[0].solicitar();
            }
            return AgendadorOutbox.Resultado.ESGOTADO;
        }, () -> {});
        referencia[0].iniciar(); tempo.avancar(1);
        assertEquals(2, chamadas.get()); referencia[0].close();
    }

    @Test void falhaImpedeRajadaDeContornarCooldown() {
        var tempo = new Tempo(); var chamadas = new AtomicInteger();
        var alvo = new AgendadorOutbox(tempo, () -> tempo.agora, () -> {
            chamadas.incrementAndGet(); return AgendadorOutbox.Resultado.FALHOU;
        }, () -> {});
        alvo.iniciar(); tempo.avancar(1); assertEquals(1, chamadas.get());
        for (int i = 0; i < 100; i++) alvo.solicitar();
        tempo.avancar(199); assertEquals(1, chamadas.get());
        tempo.avancar(1); assertEquals(2, chamadas.get()); alvo.close();
    }

    @Test void manutencaoUsaTempoENaoNumeroDeSinais() {
        var tempo = new Tempo(); var manutencoes = new AtomicInteger();
        var alvo = new AgendadorOutbox(tempo, () -> tempo.agora,
                () -> AgendadorOutbox.Resultado.ESGOTADO, manutencoes::incrementAndGet);
        alvo.iniciar(); tempo.avancar(1); int inicial = manutencoes.get();
        for (int i = 0; i < 100; i++) { alvo.solicitar(); tempo.avancar(0); }
        assertEquals(inicial, manutencoes.get());
        tempo.avancar(5200); assertTrue(manutencoes.get() > inicial); alvo.close();
    }

    @Test void manutencaoTambemOcorreEntreLotesSemSobreposicao() {
        var tempo = new Tempo(); var manutencoes = new ArrayList<Long>(); var lotes = new AtomicInteger();
        var alvo = new AgendadorOutbox(tempo, () -> tempo.agora, () -> {
            tempo.agora += TimeUnit.SECONDS.toNanos(2);
            return lotes.incrementAndGet() < 4 ? AgendadorOutbox.Resultado.CONTINUAR : AgendadorOutbox.Resultado.ESGOTADO;
        }, () -> manutencoes.add(tempo.agora));
        alvo.iniciar(); tempo.avancar(1);
        assertEquals(4, lotes.get());
        assertTrue(manutencoes.stream().anyMatch(t -> t >= TimeUnit.SECONDS.toNanos(5) && t < TimeUnit.SECONDS.toNanos(8)),
                "manutenção precisa ocorrer entre lotes, sem aguardar esvaziar a fila");
        alvo.close();
    }

    @Test void paradaRecusaNotificacoesSemPropagarErro() {
        var tempo = new Tempo(); var chamadas = new AtomicInteger();
        var alvo = new AgendadorOutbox(tempo, () -> tempo.agora, () -> {
            chamadas.incrementAndGet(); return AgendadorOutbox.Resultado.ESGOTADO;
        }, () -> {});
        alvo.iniciar(); alvo.close();
        assertDoesNotThrow(() -> { alvo.solicitar(); alvo.close(); });
        tempo.avancar(1000); assertEquals(0, chamadas.get()); assertTrue(tempo.isShutdown());
    }

    @Test void rejeicaoDeExecucaoNaoPropagaELiberaMarcaParaNovoSinal() {
        var tempo = new Tempo(); var chamadas = new AtomicInteger();
        var alvo = new AgendadorOutbox(tempo, () -> tempo.agora, () -> {
            chamadas.incrementAndGet(); return AgendadorOutbox.Resultado.ESGOTADO;
        }, () -> {});
        alvo.iniciar(); tempo.avancar(1); chamadas.set(0);
        tempo.rejeitar = true; assertDoesNotThrow(alvo::solicitar);
        tempo.rejeitar = false; alvo.solicitar(); tempo.avancar(0);
        assertEquals(1, chamadas.get()); alvo.close();
    }

    @Test void excecaoDoPublicadorNaoMataFallbackENaoContornaCooldown() {
        var tempo = new Tempo(); var chamadas = new AtomicInteger();
        var alvo = new AgendadorOutbox(tempo, () -> tempo.agora, () -> {
            chamadas.incrementAndGet(); throw new IllegalStateException("publicador indisponível QA");
        }, () -> {});
        alvo.iniciar(); tempo.avancar(1); assertEquals(1, chamadas.get());
        alvo.solicitar(); tempo.avancar(199); assertEquals(1, chamadas.get());
        tempo.avancar(1); assertEquals(2, chamadas.get()); alvo.close();
    }

    private static final class Tempo extends AbstractExecutorService implements ScheduledExecutorService {
        long agora; boolean parado; boolean rejeitar; long ordem;
        final PriorityQueue<Tarefa<?>> fila = new PriorityQueue<>();
        int pendentes() { return (int) fila.stream().filter(t -> !t.isCancelled()).count(); }
        void avancar(long millis) {
            long limite = agora + TimeUnit.MILLISECONDS.toNanos(millis); int executadas = 0;
            while (!fila.isEmpty() && fila.peek().vencimento <= Math.max(limite, agora)) {
                if (++executadas > 10000) fail("loop ocupado ou tarefas sem limite");
                var tarefa = fila.remove(); if (tarefa.isCancelled() || parado) continue;
                agora = Math.max(agora, tarefa.vencimento); tarefa.rodar();
                if (tarefa.periodo > 0 && !parado && !tarefa.isDone() && !tarefa.isCancelled()) {
                    tarefa.vencimento = agora + tarefa.periodo; fila.add(tarefa);
                }
            }
            agora = Math.max(agora, limite);
        }
        @Override public void execute(Runnable r) {
            if (rejeitar) throw new RejectedExecutionException("rejeição controlada QA");
            schedule(r, 0, TimeUnit.NANOSECONDS);
        }
        @Override public ScheduledFuture<?> schedule(Runnable r, long d, TimeUnit u) {
            return schedule(Executors.callable(r, null), d, u);
        }
        @Override public <V> ScheduledFuture<V> schedule(Callable<V> c, long d, TimeUnit u) {
            if (parado) throw new RejectedExecutionException();
            var t = new Tarefa<V>(c, agora + Math.max(0, u.toNanos(d)), 0, ordem++); fila.add(t); return t;
        }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable r, long d, long p, TimeUnit u) {
            if (parado) throw new RejectedExecutionException();
            var t = new Tarefa<Void>(Executors.callable(r, null), agora + u.toNanos(d), u.toNanos(p), ordem++);
            fila.add(t); return t;
        }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable r, long d, long p, TimeUnit u) {
            return scheduleWithFixedDelay(r,d,p,u);
        }
        @Override public void shutdown() { parado = true; }
        @Override public List<Runnable> shutdownNow() { parado = true; fila.clear(); return List.of(); }
        @Override public boolean isShutdown() { return parado; }
        @Override public boolean isTerminated() { return parado; }
        @Override public boolean awaitTermination(long t, TimeUnit u) { return parado; }
        final class Tarefa<V> extends FutureTask<V> implements ScheduledFuture<V> {
            long vencimento; final long periodo, sequencia;
            Tarefa(Callable<V> c, long v, long p, long s) { super(c); vencimento=v; periodo=p; sequencia=s; }
            void rodar() { if (periodo > 0) super.runAndReset(); else super.run(); }
            @Override public long getDelay(TimeUnit u) { return u.convert(vencimento-agora,TimeUnit.NANOSECONDS); }
            @Override public int compareTo(Delayed outro) {
                var t = (Tarefa<?>) outro; int c = Long.compare(vencimento,t.vencimento);
                return c != 0 ? c : Long.compare(sequencia,t.sequencia);
            }
        }
    }
}
