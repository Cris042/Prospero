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
    /** Compatibilidade do contrato Limit/Market; stops ainda não entram pelo adaptador Kafka. */
    public Admissao(UUID ordem, UUID intencao, String conta, String ticker, Lado lado, TipoOrdem tipo,
                    Validade validade, long quantidade, BigDecimal precoLimite, BigDecimal precoMaximo,
                    LocalDate sessao) {
        this(ordem, intencao, conta, ticker, lado, tipo, validade, quantidade, precoLimite, precoMaximo, null, sessao);
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
