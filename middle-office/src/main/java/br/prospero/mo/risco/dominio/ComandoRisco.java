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

    /** Par OCO admissível (RF05c.3b): Take Profit Limit DIA seguido de Stop Loss Stop/Stop Limit DIA com gatilho. */
    public boolean oco() {
        if (pernas.size() != 2) {
            return false;
        }
        Perna alvo = pernas.get(0), stop = pernas.get(1);
        if (alvo.tipo() != TipoOrdem.LIMIT || alvo.validade() != Validade.DIA || alvo.precoLimite() == null
                || !stop.tipo().stop()
                || stop.validade() != Validade.DIA || stop.precoGatilho() == null
                || alvo.quantidade() != stop.quantidade() || alvo.id().equals(stop.id())
                || (stop.tipo() == TipoOrdem.STOP_LIMIT) != (stop.precoLimite() != null)) {
            return false;
        }
        // Mesmas guardas do parser v3 do ME: um par admitido nunca vira mensagem venenosa (ADR-013).
        boolean compra = lado == Lado.COMPRA;
        int alvoGatilho = alvo.precoLimite().compareTo(stop.precoGatilho());
        boolean limiteCoerente = stop.precoLimite() == null || (compra
                ? stop.precoLimite().compareTo(stop.precoGatilho()) >= 0
                : stop.precoLimite().compareTo(stop.precoGatilho()) <= 0);
        return (compra ? alvoGatilho < 0 : alvoGatilho > 0) && limiteCoerente;
    }

    public boolean admissivel() {
        if (dataSessao != null && oco()) {
            return true;
        }
        return dataSessao != null && pernas.size() == 1 && pernas.getFirst().validade() != null
                && (pernas.getFirst().tipo() == TipoOrdem.LIMIT || pernas.getFirst().tipo() == TipoOrdem.MARKET
                    || pernas.getFirst().tipo().stop()
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
