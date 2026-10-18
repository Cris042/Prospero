package br.prospero.fo.ordens.dominio;

public interface RepositorioMatching {
    /** Efeito de todos os lados e deduplicação confirmam juntos; false significa repetição. */
    boolean aplicar(EventoMatching evento);
}
