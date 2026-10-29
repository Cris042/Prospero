package br.prospero.fo.cotacao.actor;

import br.prospero.fo.cotacao.dominio.CacheCotacao;
import br.prospero.fo.cotacao.dominio.RepositorioCotacao;
import br.prospero.fo.ordens.dominio.EventoMatching;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AplicarCotacaoActor {
    private final RepositorioCotacao repositorio;
    private final CacheCotacao cache;
    private final MeterRegistry metricas;

    @Inject
    public AplicarCotacaoActor(RepositorioCotacao repositorio, CacheCotacao cache, MeterRegistry metricas) {
        this.repositorio = repositorio;
        this.cache = cache;
        this.metricas = metricas;
    }

    /**
     * A projeção no PostgreSQL confirma primeiro (fonte da base do túnel, ADR-011); só então o snapshot de leitura vai
     * ao Redis (ADR-022). Falha no Redis não volta ao consumidor: o PostgreSQL continua respondendo as leituras.
     */
    public void aplicar(EventoMatching evento) {
        boolean mudou = repositorio.aplicar(evento);
        metricas.counter("prospero.cotacao.eventos", "mudou", Boolean.toString(mudou)).increment();
        if (mudou) repositorio.livro(evento.ticker()).ifPresent(cache::gravar);
    }
}
