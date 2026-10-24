package br.prospero.mo.liquidacao.dominio;

import java.util.Objects;
import java.util.UUID;

/** LIQUIDACAO_EFETIVADA v1 já validado: id derivado, assinatura canônica do envelope e crédito informado. */
public record EventoLiquidacao(UUID id, String assinatura, UUID causacao, CreditoEsperado credito) {
    public EventoLiquidacao {
        Objects.requireNonNull(id);
        Objects.requireNonNull(assinatura);
        Objects.requireNonNull(causacao);
        Objects.requireNonNull(credito);
    }
}
