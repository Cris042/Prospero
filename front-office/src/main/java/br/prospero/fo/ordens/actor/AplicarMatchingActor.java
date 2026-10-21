package br.prospero.fo.ordens.actor;

import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.dominio.RepositorioMatching;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AplicarMatchingActor {
    private final RepositorioMatching repositorio;
    private final MeterRegistry metricas;

    @Inject
    public AplicarMatchingActor(RepositorioMatching repositorio, MeterRegistry metricas) {
        this.repositorio = repositorio;
        this.metricas = metricas;
    }

    public boolean aplicar(EventoMatching evento) {
        boolean aplicado = repositorio.aplicar(evento);
        metricas.counter("prospero.ordens.matching", "aplicado", Boolean.toString(aplicado)).increment();
        return aplicado;
    }
}
