package br.prospero.bo.posicoes.actor;

import br.prospero.bo.posicoes.dominio.*;
import br.prospero.bo.posicoes.helper.CoerenciaMatching;
import br.prospero.bo.posicoes.jpa.CarteiraPosicoesJpa;
import br.prospero.bo.posicoes.jpa.CheckpointPosicoesJpa;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.TreeMap;

@ApplicationScoped
public class AplicarPosicoesActor {
    private final CheckpointPosicoesJpa checkpoint;
    private final CarteiraPosicoesJpa carteiras;
    @Inject public AplicarPosicoesActor(CheckpointPosicoesJpa checkpoint, CarteiraPosicoesJpa carteiras) {
        this.checkpoint = checkpoint;
        this.carteiras = carteiras;
    }

    @Transactional
    public void aplicar(Coordenada coordenada, EventoMatching evento) {
        CoerenciaMatching.validar(evento);
        if (!checkpoint.receber(coordenada, evento)) return;
        carteiras.validarSessao(evento);
        if (evento.detalhe() instanceof EventoMatching.Negocio negocio) aplicarNegocio(evento, negocio);
        if (evento.detalhe() instanceof EventoMatching.SessaoEncerrada) carteiras.encerrar(evento.ticker());
    }

    private void aplicarNegocio(EventoMatching evento, EventoMatching.Negocio negocio) {
        // A mesma conta nos dois lados não realiza resultado nem altera médio; cotação continua sendo fato de mercado.
        if (!negocio.compra().conta().equals(negocio.venda().conta())) {
            var deltas = new TreeMap<String, Long>();
            deltas.put(negocio.compra().conta(), negocio.quantidade());
            deltas.put(negocio.venda().conta(), -negocio.quantidade());
            deltas.forEach((conta, delta) -> {
                Posicao atual = carteiras.buscar(conta, evento.ticker());
                carteiras.salvar(conta, evento.ticker(), atual.aplicar(delta, negocio.preco()));
            });
        }
        carteiras.cotar(evento, negocio);
    }
}
