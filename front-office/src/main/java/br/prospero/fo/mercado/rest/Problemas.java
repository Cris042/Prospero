package br.prospero.fo.mercado.rest;

import br.prospero.fo.mercado.dominio.RegraMercadoException;
import br.prospero.fo.mercado.dto.ProblemaDTO;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;

import java.util.UUID;
import java.util.regex.Pattern;

/** Conversões de entrada e respostas Problem Details compartilhadas pelas rotas de mercado. */
final class Problemas {
    static final String PROBLEM_JSON = "application/problem+json";
    static final UUID NAMESPACE_PRINCIPAL = new UUID(0L, 0L);
    private static final Pattern TICKER = Pattern.compile("[A-Z][A-Z0-9]{3,7}");
    private static final Pattern CORRELACAO = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final Pattern INSTANCIA = Pattern.compile("/[A-Za-z0-9/_.-]{0,200}");
    private static final Pattern UUID_TEXTO = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private Problemas() {
    }

    static UUID namespace(String texto, boolean obrigatorio) {
        if (texto == null && !obrigatorio) {
            return NAMESPACE_PRINCIPAL;
        }
        if (texto == null || !UUID_TEXTO.matcher(texto).matches()) {
            throw new RegraMercadoException("NAMESPACE_INVALIDO", "Namespace deve ser UUID em minúsculas.");
        }
        return UUID.fromString(texto);
    }

    static String ticker(String texto) {
        if (texto == null || !TICKER.matcher(texto).matches()) {
            throw new RegraMercadoException("ATIVO_DESCONHECIDO", "Ativo não cadastrado.");
        }
        return texto;
    }

    static Response resposta(ContainerRequestContext pedido, int status, String codigo, String detalhe) {
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

    private static String instancia(String caminho) {
        return caminho != null && INSTANCIA.matcher(caminho).matches() ? caminho : "/";
    }

    static int status(String codigo) {
        return switch (codigo) {
            case "ATIVO_DESCONHECIDO", "PREPARACAO_DESABILITADA" -> 404;
            case "NAMESPACE_INVALIDO" -> 400;
            case "LIMITE_NAMESPACES" -> 429;
            default -> 422;
        };
    }

    private static String titulo(int status) {
        return switch (status) {
            case 400 -> "Requisição inválida";
            case 401 -> "Autenticação necessária";
            case 403 -> "Acesso negado";
            case 404 -> "Recurso não encontrado";
            case 405 -> "Método não permitido";
            case 415 -> "Tipo de conteúdo não suportado";
            case 429 -> "Limite atingido";
            case 422 -> "Regra de mercado violada";
            default -> "Erro na requisição";
        };
    }
}
