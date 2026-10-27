package br.prospero.fo.comum.kafka;

import org.jboss.logging.Logger;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Acordar é uma otimização volátil; somente o publicador lê a outbox persistida. */
public final class AgendadorOutbox implements AutoCloseable {
    public enum Resultado { CONTINUAR, ESGOTADO, FALHOU }
    private static final Logger LOG = Logger.getLogger(AgendadorOutbox.class);
    private static final long RECUPERACAO = TimeUnit.MILLISECONDS.toNanos(200);
    private static final long MANUTENCAO = TimeUnit.SECONDS.toNanos(5);
    private final ScheduledExecutorService agenda;
    private final LongSupplier nanoTempo;
    private final Supplier<Resultado> publicar;
    private final Runnable manter;
    private final AtomicBoolean iniciado = new AtomicBoolean();
    private final AtomicBoolean pendente = new AtomicBoolean();
    private volatile boolean fechado;
    // Acessados somente pelo executor, após inicialização anterior ao primeiro agendamento.
    private long tentarDepois;
    private long proximaManutencao;

    public AgendadorOutbox(ScheduledExecutorService agenda, LongSupplier nanoTempo,
                           Supplier<Resultado> publicar, Runnable manter) {
        this.agenda = Objects.requireNonNull(agenda);
        this.nanoTempo = Objects.requireNonNull(nanoTempo);
        this.publicar = Objects.requireNonNull(publicar);
        this.manter = Objects.requireNonNull(manter);
    }

    public synchronized void iniciar() {
        if (fechado || iniciado.get()) return;
        tentarDepois = nanoTempo.getAsLong();
        proximaManutencao = tentarDepois + MANUTENCAO;
        iniciado.set(true);
        try {
            agenda.scheduleWithFixedDelay(this::executar, 1, 200, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException erro) {
            if (!fechado) throw erro;
        }
    }

    /** No máximo uma urgência na fila, inclusive enquanto outra execução está ocupada. */
    public void solicitar() {
        if (fechado || !iniciado.get() || !pendente.compareAndSet(false, true)) return;
        try {
            agenda.execute(() -> {
                pendente.set(false);
                executar();
            });
        } catch (RejectedExecutionException erro) {
            pendente.set(false);
            // Shutdown pode ocorrer depois do teste da flag; nunca propagar ao commit confirmado.
            if (!fechado) LOG.warn("Agendamento da outbox recusado; pendencias permanecem no banco.");
        }
    }

    private void executar() {
        if (fechado || nanoTempo.getAsLong() - tentarDepois < 0) return;
        try {
            Resultado resultado;
            do {
                resultado = Objects.requireNonNull(publicar.get());
                manterSeVenceu();
                if (resultado == Resultado.FALHOU) {
                    tentarDepois = nanoTempo.getAsLong() + RECUPERACAO;
                    return;
                }
            } while (resultado == Resultado.CONTINUAR && !fechado && !Thread.currentThread().isInterrupted());
        } catch (RuntimeException erro) {
            tentarDepois = nanoTempo.getAsLong() + RECUPERACAO;
            LOG.warnf("Ciclo da outbox falhou; nova tentativa limitada: %s", erro.getClass().getSimpleName());
        }
    }

    private void manterSeVenceu() {
        if (fechado || nanoTempo.getAsLong() - proximaManutencao < 0) return;
        // Falha de manutenção também conserva cadência; não tentar expurgo a cada sinal novo.
        proximaManutencao = nanoTempo.getAsLong() + MANUTENCAO;
        manter.run();
    }

    @Override
    public void close() {
        fechado = true;
        pendente.set(false);
        agenda.shutdownNow();
        try {
            if (!agenda.awaitTermination(2, TimeUnit.SECONDS)) {
                LOG.warn("Executor da outbox ainda encerrando; pendencias persistidas serao recuperadas.");
            }
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
        }
    }
}
