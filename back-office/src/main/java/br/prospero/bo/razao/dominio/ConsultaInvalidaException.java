package br.prospero.bo.razao.dominio;

/** Parâmetro de consulta recusado com resposta genérica; a mensagem nunca ecoa a entrada. */
public class ConsultaInvalidaException extends RuntimeException {
    public ConsultaInvalidaException() { super("CONSULTA_INVALIDA"); }
}
