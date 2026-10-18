package br.prospero.fo.ordens.dominio;

/** Fato semanticamente incompatível: bloqueia a sequência financeira sem expor conteúdo externo. */
public class MatchingIncoerenteException extends RuntimeException {
    public MatchingIncoerenteException(String motivo) {
        super(motivo);
    }
}
