package br.prospero.bo.liquidacao.kafka;

import br.prospero.bo.liquidacao.jpa.LiquidacaoJpa;
import br.prospero.bo.posicoes.dominio.ProjecaoIncoerenteException;
import br.prospero.bo.posicoes.dominio.SituacaoPosicoes;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Um lote limitado por ciclo, retomado após reinício. O worker não participa da prontidão. */
@ApplicationScoped
public class WorkerLiquidacao {
    private static final Logger LOG = Logger.getLogger(WorkerLiquidacao.class);
    @Inject LiquidacaoJpa liquidacao;
    @Inject SituacaoPosicoes situacao;
    @ConfigProperty(name="prospero.liquidacao.worker-habilitado", defaultValue="true") boolean habilitado;
    private ScheduledExecutorService agenda;
    private volatile boolean quarentena;
    void iniciar(@Observes StartupEvent evento) {
        if (!habilitado) return;
        agenda = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("liquidacao-d2").daemon().factory());
        agenda.scheduleWithFixedDelay(this::ciclo,1,1,TimeUnit.SECONDS);
    }
    void parar(@Observes ShutdownEvent evento) { if (agenda != null) agenda.shutdownNow(); }
    void ciclo() {
        try {
            if (quarentena) {
                // Persistência do marcador pode falhar transitoriamente; nunca encerrar a agenda nem repetir efeitos.
                liquidacao.quarentena();
                return;
            }
            if (!situacao.emDia()) return;
            try { liquidacao.processarLote(); }
            catch (RuntimeException erro) {
                if (!LiquidacaoJpa.invariante(erro)) throw erro;
                quarentena = true;
                liquidacao.quarentena();
                LOG.error("Liquidação em quarentena: INVARIANTE_LIQUIDACAO");
            }
        } catch (RuntimeException erro) {
            LOG.warnf("Liquidação não confirmada; erro técnico: %s",erro.getClass().getSimpleName());
        }
    }
}
