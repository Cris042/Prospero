package br.prospero.bo.razao.dominio;

/** Inexistente, malformado ou sem participação da conta: a mesma resposta, sem distinguir os casos. */
public class NegocioNaoEncontradoException extends RuntimeException {
    public NegocioNaoEncontradoException() { super("NEGOCIO_NAO_ENCONTRADO"); }
}
