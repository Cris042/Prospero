package br.prospero.fo.cotacao.dominio;

import java.math.BigDecimal;

public record NivelCotado(BigDecimal preco, long quantidade, int ofertas, long quantidadeAcumulada) {
}
