package br.prospero.me.livro.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Ordem com reserva (ADR-013). precoLimite em Limit/Stop Limit; precoMaximo é o preço unitário máximo
 * reservado da compra (null na venda). sessao vem do comando: o núcleo não lê relógio.
 */
public record Admissao(UUID ordem, UUID intencao, String conta, String ticker, Lado lado, TipoOrdem tipo,
                       Validade validade, long quantidade, BigDecimal precoLimite, BigDecimal precoMaximo,
                       BigDecimal precoGatilho, LocalDate sessao) {
    /** Compatibilidade do contrato Limit/Market v1; stops usam gatilho no contrato v2. */
    public Admissao(UUID ordem, UUID intencao, String conta, String ticker, Lado lado, TipoOrdem tipo,
                    Validade validade, long quantidade, BigDecimal precoLimite, BigDecimal precoMaximo,
                    LocalDate sessao) {
        this(ordem, intencao, conta, ticker, lado, tipo, validade, quantidade, precoLimite, precoMaximo, null, sessao);
    }

    /** Mesma ordem com quantidade menor, para a perna OCO reduzida pela irmã. */
    Admissao reduzida(long quantidade) {
        return new Admissao(ordem, intencao, conta, ticker, lado, tipo, validade, quantidade, precoLimite, precoMaximo,
                precoGatilho, sessao);
    }

    /**
     * Combinação tipo × validade × preços × lado (ADR-010/013): Market nunca DIA; stops só DIA; cada tipo com
     * exatamente os preços que lhe cabem; preço máximo só na compra; Stop Limit com limite do lado certo do gatilho
     * (compra: limite ≥ gatilho; venda: limite ≤ gatilho). Fonte única para o parser do comando e para o núcleo.
     */
    public boolean combinacaoValida() {
        boolean compra = lado == Lado.COMPRA;
        if ((tipo == TipoOrdem.MARKET && validade == Validade.DIA) || (tipo.stop() && validade != Validade.DIA)
                || tipo.comLimite() != (precoLimite != null) || tipo.stop() != (precoGatilho != null)
                || compra != (precoMaximo != null)) {
            return false;
        }
        if (tipo != TipoOrdem.STOP_LIMIT) {
            return true;
        }
        int comparacao = precoLimite.compareTo(precoGatilho);
        return compra ? comparacao >= 0 : comparacao <= 0;
    }

    /** Compra com limite acima do preço unitário máximo reservado; pressupõe {@link #combinacaoValida()}. */
    public boolean limiteAcimaDaReserva() {
        return lado == Lado.COMPRA && tipo.comLimite() && precoLimite.compareTo(precoMaximo) > 0;
    }

    /** OCO (ADR-010): alvo deste Take Profit além do gatilho do Stop Loss — venda acima, compra abaixo. */
    public boolean alvoAlemDoGatilho(Admissao stopLoss) {
        if (precoLimite == null || stopLoss.precoGatilho == null) {
            return false;
        }
        int comparacao = precoLimite.compareTo(stopLoss.precoGatilho);
        return lado == Lado.VENDA ? comparacao > 0 : comparacao < 0;
    }

    public Admissao {
        Objects.requireNonNull(ordem);
        Objects.requireNonNull(intencao);
        Objects.requireNonNull(conta);
        Objects.requireNonNull(ticker);
        Objects.requireNonNull(lado);
        Objects.requireNonNull(tipo);
        Objects.requireNonNull(validade);
        Objects.requireNonNull(sessao);
        if (quantidade <= 0) {
            throw new IllegalArgumentException("Quantidade deve ser positiva.");
        }
    }
}
