package br.prospero.bo.posicoes.dominio;

public class PosicoesIndisponiveisException extends RuntimeException {
    public PosicoesIndisponiveisException() { super("Projeção de posições indisponível ou em reconstrução."); }
}
