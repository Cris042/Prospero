package br.prospero.mo.matching.actor;

import br.prospero.mo.matching.jpa.RepositorioMatchingJpa;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class AplicarMatchingActor {
    @Inject RepositorioMatchingJpa repositorio;
    @Transactional
    public void aplicar(JsonNode evento) { repositorio.aplicar(evento); }
}
