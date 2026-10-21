package br.prospero.mo.risco.dominio;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Uma ordem da intenção: simples (uma perna) ou par OCO (duas). Preços ausentes são null conforme o tipo. */
public record Perna(UUID id, TipoOrdem tipo, long quantidade, BigDecimal precoLimite, BigDecimal precoGatilho, Validade validade) {
    public Perna(UUID id, TipoOrdem tipo, long quantidade, BigDecimal precoLimite, BigDecimal precoGatilho) {
        this(id, tipo, quantidade, precoLimite, precoGatilho, null);
    }

    public Perna {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tipo);
        if (quantidade <= 0) {
            throw new IllegalArgumentException("Quantidade deve ser positiva.");
        }
    }
}
