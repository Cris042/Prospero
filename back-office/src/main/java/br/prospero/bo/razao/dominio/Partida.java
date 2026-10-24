package br.prospero.bo.razao.dominio;

/** Um lado de um lançamento em partidas dobradas. */
public record Partida(ContaContabil conta, Natureza natureza, Montante montante) {
    public Partida {
        if (conta == null || natureza == null || montante == null) throw new IllegalArgumentException("Partida incompleta.");
    }
}
