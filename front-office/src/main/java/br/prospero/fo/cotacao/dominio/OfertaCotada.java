package br.prospero.fo.cotacao.dominio;

import java.math.BigDecimal;

/** Oferta em repouso, sem identificar conta, ordem ou intenção. */
public record OfertaCotada(String lado, BigDecimal preco, long restante) {
}
