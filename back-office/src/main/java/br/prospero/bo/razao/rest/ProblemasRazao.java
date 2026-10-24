package br.prospero.bo.razao.rest;

import br.prospero.bo.posicoes.rest.ProblemasPosicoes;
import br.prospero.bo.razao.dominio.ConsultaInvalidaException;
import br.prospero.bo.razao.dominio.NegocioNaoEncontradoException;
import br.prospero.bo.razao.dominio.RazaoIndisponivelException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Problem Details genéricos: nenhuma mensagem ecoa cursor, identificador ou conta recebidos. */
public class ProblemasRazao {
    @ServerExceptionMapper
    public Response invalida(ConsultaInvalidaException erro, ContainerRequestContext pedido) {
        return ProblemasPosicoes.problema(400, "CONSULTA_INVALIDA", "Cursor ou limite inválido.", pedido);
    }

    @ServerExceptionMapper
    public Response naoEncontrado(NegocioNaoEncontradoException erro, ContainerRequestContext pedido) {
        return ProblemasPosicoes.problema(404, "NEGOCIO_NAO_ENCONTRADO", "Negócio não encontrado para esta conta.", pedido);
    }

    @ServerExceptionMapper
    public Response indisponivel(RazaoIndisponivelException erro, ContainerRequestContext pedido) {
        return ProblemasPosicoes.problema(503, "RAZAO_INDISPONIVEL", "Razão indisponível ou em reconstrução; tente novamente.", pedido);
    }
}
