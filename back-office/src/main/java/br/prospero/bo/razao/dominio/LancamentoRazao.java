package br.prospero.bo.razao.dominio;

import java.time.LocalDate;
import java.util.UUID;

/** Partida persistida da própria conta; lançamentos contra a Câmara não identificam a outra conta. */
public record LancamentoRazao(long sequencia, UUID transacao, String negocio, String ticker, LadoNegocio lado,
                              Momento momento, LocalDate data, Partida partida) {
}
