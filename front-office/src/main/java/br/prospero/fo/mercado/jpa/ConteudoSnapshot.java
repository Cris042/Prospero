package br.prospero.fo.mercado.jpa;

import br.prospero.fo.mercado.dominio.Lado;

import java.util.List;

/** Forma persistida em JSONB; decimais textuais preservam escala sem ponto flutuante. */
record ConteudoSnapshot(List<OfertaJson> ofertas, List<NegocioJson> negocios) {
    record OfertaJson(Lado lado, String preco, long quantidade) {
    }

    record NegocioJson(String preco, long quantidade) {
    }
}
