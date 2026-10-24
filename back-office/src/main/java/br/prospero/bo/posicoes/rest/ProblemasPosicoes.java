package br.prospero.bo.posicoes.rest;

import br.prospero.bo.posicoes.dominio.AcessoPosicoesException;
import br.prospero.bo.posicoes.dominio.PosicoesIndisponiveisException;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import java.util.UUID;

@Provider
public class ProblemasPosicoes implements ContainerResponseFilter {
    public record ProblemaDTO(String type, String title, int status, String codigo, String detail, String instance) { }

    @Override public void filter(ContainerRequestContext pedido, ContainerResponseContext resposta) {
        String caminho = pedido.getUriInfo().getPath();
        if (caminho.startsWith("api/v1/posicoes") || caminho.startsWith("api/v1/negocios") || caminho.startsWith("api/v1/razao")) {
            resposta.getHeaders().putSingle("Cache-Control", "no-store");
        }
    }

    @ServerExceptionMapper({UnauthorizedException.class, AuthenticationFailedException.class})
    public Response autenticacao(ContainerRequestContext pedido) {
        return Response.fromResponse(problema(401, "AUTENTICACAO_INVALIDA", "Token ausente, inválido ou expirado.", pedido))
                .header("WWW-Authenticate", "Bearer").build();
    }

    @ServerExceptionMapper({ForbiddenException.class, AcessoPosicoesException.class})
    public Response proibido(ContainerRequestContext pedido) { return problema(403, "ACESSO_NEGADO", "Conta ou escopo não permitido.", pedido); }

    @ServerExceptionMapper
    public Response indisponivel(PosicoesIndisponiveisException erro, ContainerRequestContext pedido) {
        return problema(503, "POSICOES_INDISPONIVEIS", "Projeção indisponível ou em reconstrução; tente novamente.", pedido);
    }

    @ServerExceptionMapper
    public Response protocolo(WebApplicationException erro, ContainerRequestContext pedido) {
        return problema(erro.getResponse().getStatus(), "ERRO_HTTP", "Requisição recusada pelo protocolo HTTP.", pedido);
    }

    public static Response problema(int status, String codigo, String mensagem, ContainerRequestContext pedido) {
        String caminho = pedido.getUriInfo().getPath();
        String instancia = caminho.matches("[A-Za-z0-9/_.-]{0,200}") ? "/" + caminho : "/";
        return Response.status(status).type("application/problem+json").header("Cache-Control", "no-store")
                .header("X-Request-ID", UUID.randomUUID().toString())
                .entity(new ProblemaDTO("about:blank", mensagem, status, codigo, mensagem, instancia)).build();
    }
}
