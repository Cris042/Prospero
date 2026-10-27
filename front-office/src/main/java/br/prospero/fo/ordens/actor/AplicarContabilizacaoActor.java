package br.prospero.fo.ordens.actor;

import br.prospero.fo.ordens.dominio.EventoContabilizacao;
import br.prospero.fo.ordens.jpa.RepositorioTrajetoriaJpa;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AplicarContabilizacaoActor {
    private final RepositorioTrajetoriaJpa trajetoria;
    @Inject
    public AplicarContabilizacaoActor(RepositorioTrajetoriaJpa trajetoria) { this.trajetoria = trajetoria; }
    public boolean aplicar(EventoContabilizacao evento) { return trajetoria.registrarContabilizacao(evento); }
}
