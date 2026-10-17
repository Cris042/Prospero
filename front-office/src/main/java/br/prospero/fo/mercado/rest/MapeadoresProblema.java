package br.prospero.fo.mercado.rest;

import br.prospero.fo.mercado.dominio.RegraMercadoException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Erros de entrada viram Problem Details sem refletir conteúdo enviado. */
public class MapeadoresProblema {
    @ServerExceptionMapper
    public Response regra(RegraMercadoException erro) {
        return Problemas.resposta(Problemas.status(erro.codigo()), erro.codigo(), erro.getMessage());
    }

    @ServerExceptionMapper
    public Response validacao(ConstraintViolationException erro) {
        return Problemas.resposta(400, "CORPO_INVALIDO", "Campos obrigatórios ausentes ou fora dos limites.");
    }

    @ServerExceptionMapper
    public Response json(JsonProcessingException erro) {
        return Problemas.resposta(400, "JSON_INVALIDO", "Corpo JSON malformado ou com campos desconhecidos.");
    }

    @ServerExceptionMapper(priority = 1)
    public Response tipoJson(MismatchedInputException erro) {
        return json(erro);
    }

    @ServerExceptionMapper
    public Response aritmetica(ArithmeticException erro) {
        return Problemas.resposta(422, "LIMITE_EXCEDIDO", "Totais excedem a capacidade aceita.");
    }
}
