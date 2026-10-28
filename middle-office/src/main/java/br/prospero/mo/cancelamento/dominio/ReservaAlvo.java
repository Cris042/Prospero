package br.prospero.mo.cancelamento.dominio;

import java.time.LocalDate;

/**
 * Reserva com admissão comprovada (ordem, sessão e recibo de publicação) que o pedido pretende cancelar. Ordem é nula
 * no par OCO, cuja reserva pertence ao grupo.
 */
public record ReservaAlvo(java.util.UUID ordem, String conta, String ticker, LocalDate sessao, boolean oco,
                          boolean encerrada) {
}
