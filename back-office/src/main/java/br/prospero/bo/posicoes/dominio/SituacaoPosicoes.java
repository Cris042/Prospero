package br.prospero.bo.posicoes.dominio;

public interface SituacaoPosicoes {
    /** Em dia para leituras: lag até o limite configurado e verificação recente. */
    boolean emDia();

    /** Barreira estrita: em dia e sem nenhum registro pendente (lag 0). */
    default boolean semLag() { return emDia(); }
}
