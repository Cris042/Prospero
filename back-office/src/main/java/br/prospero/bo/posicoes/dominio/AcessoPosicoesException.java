package br.prospero.bo.posicoes.dominio;

public class AcessoPosicoesException extends RuntimeException {
    public AcessoPosicoesException() { super("Conta não permitida."); }
}
