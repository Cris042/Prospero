package br.prospero.fo.cotacao.dominio;

/** Entrada do cache de leitura (ADR-022): {@code livro} nulo registra que o ativo ainda não tem cotação. */
public record CotacaoEmCache(LivroCotado livro) {
}
