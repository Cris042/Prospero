package br.prospero.bo.liquidacao.rest;

import br.prospero.bo.liquidacao.dominio.MovimentoInvalidoException;
import br.prospero.bo.posicoes.rest.ProblemasPosicoes;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

public class ProblemasMovimento {
    @ServerExceptionMapper
    public Response invalido(MovimentoInvalidoException erro, ContainerRequestContext pedido) {
        return ProblemasPosicoes.problema(erro.status(),erro.codigo(),"Avanço de data recusado.",pedido);
    }
}
