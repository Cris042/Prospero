package br.prospero.fo.ordens.actor;

import br.prospero.fo.ordens.dominio.RepositorioOrdens;
import br.prospero.fo.ordens.dominio.ResultadoRisco;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Passo da SAGA (ADR-002/ADR-012): aplica o resultado do risco às ordens ainda RECEBIDA da mesma conta.
 * Resultado repetido, de outra conta ou para ordem já decidida não altera nada (idempotente).
 */
@ApplicationScoped
public class AplicarRiscoActor {
    private static final Logger LOG = Logger.getLogger(AplicarRiscoActor.class);
    private final RepositorioOrdens repositorio;
    private final MeterRegistry metricas;

    @Inject
    public AplicarRiscoActor(RepositorioOrdens repositorio, MeterRegistry metricas) {
        this.repositorio = repositorio;
        this.metricas = metricas;
    }

    public boolean aplicar(ResultadoRisco resultado) {
        int alteradas = repositorio.aplicarRisco(resultado);
        metricas.counter("prospero.ordens.risco", "estado", resultado.estado().name(),
                "aplicado", Boolean.toString(alteradas > 0)).increment();
        if (alteradas == 0) {
            LOG.infof("Resultado de risco sem efeito (repetido ou fora de RECEBIDA): envio=%s evento=%s",
                    resultado.grupoEnvio(), resultado.evento());
            return false;
        }
        LOG.infof("Risco aplicado: envio=%s conta=%s estado=%s motivo=%s ordens=%d", resultado.grupoEnvio(),
                resultado.conta(), resultado.estado(), resultado.motivo(), alteradas);
        return true;
    }
}
