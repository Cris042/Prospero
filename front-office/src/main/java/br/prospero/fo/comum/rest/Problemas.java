package br.prospero.fo.comum.rest;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.dto.ProblemaDTO;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;

import java.util.UUID;
import java.util.regex.Pattern;

/** Respostas Problem Details (RFC 9457) compartilhadas pelas rotas do front office. */
public final class Problemas {
    public static final String PROBLEM_JSON = "application/problem+json";
    private static final Pattern CORRELACAO = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final Pattern INSTANCIA = Pattern.compile("/[A-Za-z0-9/_.-]{0,200}");

    private Problemas() {
    }

    public static Response resposta(ContainerRequestContext pedido, int status, String codigo, String detalhe) {
        // instance é o caminho já validado pelo roteador; correlação só se for um request id seguro.
        String correlacao = pedido.getHeaderString("X-Request-ID");
        if (correlacao == null || !CORRELACAO.matcher(correlacao).matches()) {
            correlacao = UUID.randomUUID().toString();
        }
        return Response.status(status).type(PROBLEM_JSON).header("Cache-Control", "no-store")
                .header("X-Request-ID", correlacao)
                .entity(new ProblemaDTO("about:blank", titulo(status), status, codigo, detalhe,
                        instancia(pedido.getUriInfo().getPath()), correlacao)).build();
    }

    public static int status(Categoria categoria) {
        return switch (categoria) {
            case ENTRADA_INVALIDA -> 400;
            case NAO_ENCONTRADO -> 404;
            case CONFLITO -> 409;
            case REGRA_VIOLADA -> 422;
            case LIMITE_ATINGIDO -> 429;
            case PROIBIDO -> 403;
        };
    }

    private static String instancia(String caminho) {
        return caminho != null && INSTANCIA.matcher(caminho).matches() ? caminho : "/";
    }

    private static String titulo(int status) {
        return switch (status) {
            case 400 -> "Requisição inválida";
            case 401 -> "Autenticação necessária";
            case 403 -> "Acesso negado";
            case 404 -> "Recurso não encontrado";
            case 405 -> "Método não permitido";
            case 409 -> "Conflito";
            case 415 -> "Tipo de conteúdo não suportado";
            case 422 -> "Regra de negócio violada";
            case 429 -> "Limite atingido";
            default -> "Erro na requisição";
        };
    }
}
