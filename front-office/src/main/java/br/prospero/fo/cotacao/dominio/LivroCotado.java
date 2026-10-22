package br.prospero.fo.cotacao.dominio;

public record LivroCotado(SessaoCotacao sessao, LadoCotado compras, LadoCotado vendas) {
}
