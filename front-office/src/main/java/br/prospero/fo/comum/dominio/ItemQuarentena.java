package br.prospero.fo.comum.dominio;

import java.time.Instant;

/** Linha da quarentena; {@code resolucao} nula enquanto a mensagem aguarda reprocessamento ou descarte. */
public record ItemQuarentena(long id, MensagemQuarentena mensagem, Instant quarentenadoEm, ResolucaoQuarentena resolucao,
                             Instant resolvidoEm) {
    public boolean pendente() { return resolucao == null; }
}
