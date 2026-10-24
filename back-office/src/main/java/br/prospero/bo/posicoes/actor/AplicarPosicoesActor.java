package br.prospero.bo.posicoes.actor;

import br.prospero.bo.posicoes.dominio.*;
import br.prospero.bo.posicoes.helper.CoerenciaMatching;
import br.prospero.bo.posicoes.jpa.CarteiraPosicoesJpa;
import br.prospero.bo.posicoes.jpa.CheckpointPosicoesJpa;
import br.prospero.bo.razao.dominio.FatoNegocio;
import br.prospero.bo.razao.helper.PlanoDeContas;
import br.prospero.bo.razao.jpa.RazaoJpa;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.TreeMap;

@ApplicationScoped
public class AplicarPosicoesActor {
    private final CheckpointPosicoesJpa checkpoint;
    private final CarteiraPosicoesJpa carteiras;
    private final RazaoJpa razao;
    @Inject public AplicarPosicoesActor(CheckpointPosicoesJpa checkpoint, CarteiraPosicoesJpa carteiras, RazaoJpa razao) {
        this.checkpoint = checkpoint;
        this.carteiras = carteiras;
        this.razao = razao;
    }

    /** RF07 + RF08b (ADR-016): fato, lançamentos D0, liquidação pendente, posições e checkpoint numa só transação. */
    @Transactional
    public void aplicar(Coordenada coordenada, EventoMatching evento) {
        CoerenciaMatching.validar(evento);
        if (!checkpoint.receber(coordenada, evento)) return;
        carteiras.validarSessao(evento);
        if (evento.detalhe() instanceof EventoMatching.Negocio negocio) aplicarNegocio(evento, negocio);
        if (evento.detalhe() instanceof EventoMatching.SessaoEncerrada) carteiras.encerrar(evento.ticker());
    }

    private void aplicarNegocio(EventoMatching evento, EventoMatching.Negocio negocio) {
        FatoNegocio fato = FatoNegocio.de(evento, negocio, razao.calendario());
        razao.registrar(fato, PlanoDeContas.d0(fato));
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
