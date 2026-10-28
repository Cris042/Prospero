package br.prospero.mo.cancelamento.dominio;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Pedido de cancelamento já validado pelo leitor: identidade da ordem alvo, sem o conteúdo bruto do envelope. */
public record SolicitacaoCancelamento(UUID id, UUID ordem, UUID intencao, String conta, String ticker,
                                      LocalDate dataSessao) {
    public SolicitacaoCancelamento {
        Objects.requireNonNull(id);
        Objects.requireNonNull(ordem);
        Objects.requireNonNull(intencao);
        Objects.requireNonNull(conta);
        Objects.requireNonNull(ticker);
        Objects.requireNonNull(dataSessao);
    }
}
