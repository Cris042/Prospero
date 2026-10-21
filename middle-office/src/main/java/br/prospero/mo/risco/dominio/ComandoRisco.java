package br.prospero.mo.risco.dominio;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pedido de avaliação recebido do FO. intencao identifica a ordem simples ou o par OCO e é a chave de deduplicação;
 * precoBase é a base do túnel calculada pelo FO (último da sessão ou referência), usada só para compra Market.
 */
public record ComandoRisco(UUID evento, UUID intencao, String conta, String ticker, Lado lado, BigDecimal precoBase,
                           List<Perna> pernas, String traceparent, java.time.LocalDate dataSessao) {
    public ComandoRisco(UUID evento, UUID intencao, String conta, String ticker, Lado lado, BigDecimal precoBase,
                        List<Perna> pernas, String traceparent) {
        this(evento, intencao, conta, ticker, lado, precoBase, pernas, traceparent, null);
    }

    public boolean admissivel() {
        return dataSessao != null && pernas.size() == 1 && pernas.getFirst().validade() != null
                && (pernas.getFirst().tipo() == TipoOrdem.LIMIT || pernas.getFirst().tipo() == TipoOrdem.MARKET
                    || (pernas.getFirst().tipo() == TipoOrdem.STOP || pernas.getFirst().tipo() == TipoOrdem.STOP_LIMIT)
                        && pernas.getFirst().validade() == Validade.DIA && pernas.getFirst().precoGatilho() != null);
    }

    public ComandoRisco {
        Objects.requireNonNull(evento);
        Objects.requireNonNull(intencao);
        Objects.requireNonNull(conta);
        Objects.requireNonNull(ticker);
        Objects.requireNonNull(lado);
        pernas = List.copyOf(pernas);
        if (pernas.isEmpty() || pernas.size() > 2) {
            throw new IllegalArgumentException("Intenção tem uma ou duas pernas.");
        }
    }
}
