package br.prospero.fo.mercado.rest;

import br.prospero.fo.mercado.dominio.RegraMercadoException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Erros viram Problem Details com ocorrência e correlação, sem refletir conteúdo enviado. */
public class MapeadoresProblema {
    @ServerExceptionMapper
    public Response regra(RegraMercadoException erro, ContainerRequestContext pedido) {
        return Problemas.resposta(pedido, Problemas.status(erro.codigo()), erro.codigo(), erro.getMessage());
    }

    @ServerExceptionMapper
    public Response validacao(ConstraintViolationException erro, ContainerRequestContext pedido) {
        return Problemas.resposta(pedido, 400, "CORPO_INVALIDO", "Campos obrigatórios ausentes ou fora dos limites.");
    }

    @ServerExceptionMapper
    public Response json(JsonProcessingException erro, ContainerRequestContext pedido) {
        return Problemas.resposta(pedido, 400, "JSON_INVALIDO", "Corpo JSON malformado, com tipo incorreto ou campos desconhecidos.");
    }

    @ServerExceptionMapper(priority = 1)
    public Response tipoJson(MismatchedInputException erro, ContainerRequestContext pedido) {
        return json(erro, pedido);
    }

    @ServerExceptionMapper({UnauthorizedException.class, AuthenticationFailedException.class})
    public Response naoAutenticado(ContainerRequestContext pedido) {
        return Response.fromResponse(Problemas.resposta(pedido, 401, "AUTENTICACAO_INVALIDA",
                "Token ausente, inválido ou expirado.")).header("WWW-Authenticate", "Bearer").build();
    }

    @ServerExceptionMapper
    public Response proibido(ForbiddenException erro, ContainerRequestContext pedido) {
        return Problemas.resposta(pedido, 403, "PERMISSAO_INSUFICIENTE", "Token sem o escopo exigido.");
    }

    @ServerExceptionMapper
    public Response http(WebApplicationException erro, ContainerRequestContext pedido) {
        int status = erro.getResponse().getStatus();
        String codigo = switch (status) {
            case 400 -> "JSON_INVALIDO";
            case 404 -> "RECURSO_INEXISTENTE";
            case 405 -> "METODO_NAO_PERMITIDO";
            case 415 -> "TIPO_NAO_SUPORTADO";
            default -> "ERRO_HTTP";
        };
        return Problemas.resposta(pedido, status, codigo, "Requisição recusada pelo protocolo HTTP.");
    }
}
