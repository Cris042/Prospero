package br.prospero.fo.cotacao.actor;

import br.prospero.fo.cotacao.dominio.RepositorioCotacao;
import br.prospero.fo.ordens.dominio.EventoMatching;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AplicarCotacaoActor {
    private final RepositorioCotacao repositorio;
    private final MeterRegistry metricas;

    @Inject
    public AplicarCotacaoActor(RepositorioCotacao repositorio, MeterRegistry metricas) {
        this.repositorio = repositorio;
        this.metricas = metricas;
    }

    public void aplicar(EventoMatching evento) {
        boolean mudou = repositorio.aplicar(evento);
        metricas.counter("prospero.cotacao.eventos", "mudou", Boolean.toString(mudou)).increment();
    }
}
