package br.prospero.fo.cotacao.dominio;

import java.util.List;

public record LadoCotado(List<NivelCotado> niveis, int niveisTotal, long quantidadeTotal) {
    public LadoCotado {
        niveis = List.copyOf(niveis);
    }
}
