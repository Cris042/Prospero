package br.prospero.mo.liquidacao.dominio;

import java.util.Objects;
import java.util.UUID;

/** Liquidação recebida antes da expectativa, guardada por (id, assinatura) com a posição de origem no tópico. */
public record LiquidacaoAguardando(UUID id, String assinatura, CreditoEsperado credito, int particao, long deslocamento) {
    public LiquidacaoAguardando {
        Objects.requireNonNull(id);
        Objects.requireNonNull(assinatura);
        Objects.requireNonNull(credito);
    }
}
