package br.prospero.fo.cotacao;

import br.prospero.fo.cotacao.actor.AplicarCotacaoActor;
import br.prospero.fo.cotacao.dominio.CacheCotacao;
import br.prospero.fo.cotacao.dominio.CotacaoEmCache;
import br.prospero.fo.cotacao.dominio.LivroCotado;
import br.prospero.fo.ordens.dominio.EventoMatching;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-022: o snapshot vai ao Redis só depois que o PostgreSQL aplicou um evento que mudou o mercado. */
class AplicarCotacaoActorTest {
    @Test
    void gravaNoCacheSoQuandoOEventoMudouAProjecao() {
        var livro = SerializadorCotacaoTest.livro(LocalDate.of(2026, 10, 28), 3, "10.00");
        var gravados = new ArrayList<LivroCotado>();
        var repositorio = new RepositorioCotacaoMemoria() {
            boolean mudar = true;
            @Override public boolean aplicar(EventoMatching evento) { return mudar; }
        };
        repositorio.preparar(livro);
        CacheCotacao cache = new CacheCotacao() {
            @Override public Optional<CotacaoEmCache> ler(String ticker) { return Optional.empty(); }
            @Override public void gravar(LivroCotado l) { gravados.add(l); }
            @Override public void gravarAusente(String ticker) { fail("não grava ausente na aplicação"); }
        };
        var actor = new AplicarCotacaoActor(repositorio, cache, new SimpleMeterRegistry());
        var evento = new EventoMatching(UUID.randomUUID(), UUID.randomUUID(), "ALFA3", LocalDate.of(2026, 10, 28), "a", null);
        actor.aplicar(evento);
        assertEquals(List.of(livro), gravados);
        repositorio.mudar = false;
        actor.aplicar(evento);
        assertEquals(1, gravados.size(), "reentrega ou fato sem efeito não regrava");
    }
}
