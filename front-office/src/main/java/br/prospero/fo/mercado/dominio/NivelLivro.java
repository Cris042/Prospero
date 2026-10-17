package br.prospero.fo.mercado.dominio;

import java.math.BigDecimal;

/** Nível agregado por preço, com quantidade acumulada desde o melhor preço. */
public record NivelLivro(BigDecimal preco, long quantidade, int ofertas, long quantidadeAcumulada) {
}
