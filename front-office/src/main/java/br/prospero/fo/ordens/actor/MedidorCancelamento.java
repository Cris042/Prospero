package br.prospero.fo.ordens.actor;

import br.prospero.fo.ordens.dominio.RepositorioCancelamento;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/** Idade da operação de cancelamento mais antiga ainda sem resultado final (0 sem pendência), para alerta. */
@ApplicationScoped
public class MedidorCancelamento {
    private final RepositorioCancelamento cancelamentos;
    private final MeterRegistry metricas;

    @Inject
    public MedidorCancelamento(RepositorioCancelamento cancelamentos, MeterRegistry metricas) {
        this.cancelamentos = cancelamentos;
        this.metricas = metricas;
    }

    void iniciar(@Observes StartupEvent evento) {
        Gauge.builder("prospero.cancelamento.pendente.segundos.max", cancelamentos, c -> (double) c.pendenteSegundosMax())
                .description("Idade, em segundos, da operação de cancelamento pendente mais antiga").register(metricas);
    }
}
