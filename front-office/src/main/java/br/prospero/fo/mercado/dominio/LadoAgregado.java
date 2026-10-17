package br.prospero.fo.mercado.dominio;

import java.util.List;

/** Níveis exibidos (até cinco) e totais conhecidos do lado inteiro. */
public record LadoAgregado(List<NivelLivro> niveis, int niveisTotal, long quantidadeTotal) {
    public LadoAgregado {
        niveis = List.copyOf(niveis);
    }
}
