package br.prospero.fo.mercado.dominio;

import java.math.BigDecimal;

/** Indicadores calculados; valores ausentes permanecem nulos, nunca zero. */
public record Indicadores(BigDecimal ultimoPreco, BigDecimal variacao, BigDecimal variacaoPercentual,
                          BigDecimal melhorCompra, BigDecimal melhorVenda, BigDecimal spread,
                          Long volumeQuantidade, BigDecimal volumeFinanceiro, EstadoLivro estadoLivro) {
}
