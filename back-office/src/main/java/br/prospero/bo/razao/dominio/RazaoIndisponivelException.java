package br.prospero.bo.razao.dominio;

/** Bootstrap, atraso ou quarentena técnica: nunca responder histórico vazio como confirmado. */
public class RazaoIndisponivelException extends RuntimeException {
    public RazaoIndisponivelException() { super("RAZAO_INDISPONIVEL"); }
}
