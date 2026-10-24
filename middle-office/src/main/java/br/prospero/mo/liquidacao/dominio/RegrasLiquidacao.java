package br.prospero.mo.liquidacao.dominio;

import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/** Regras puras do crédito D+2 no MO (ADR-016); estado lido e travado pelo repositório antes da decisão. */
public final class RegrasLiquidacao {
    private RegrasLiquidacao() {
    }

    public record Recusa(LiquidacaoAguardando aguardando, MotivoRecusa motivo) {
    }

    /** O que fazer com as liquidações que aguardavam a expectativa recém-gravada do mesmo id. */
    public record Resolucao(Optional<LiquidacaoAguardando> aplicar, List<Recusa> recusar) {
        public Resolucao {
            recusar = List.copyOf(recusar);
        }
    }

    /**
     * Desfecho de um evento validado. Crédito aplicado decide primeiro (reentrega idêntica sem efeito); depois a
     * expectativa; sem ela, o evento aguarda até o teto. A contagem do aguardando só é consultada sem expectativa.
     */
    public static Desfecho desfecho(EventoLiquidacao evento, Optional<Expectativa> expectativa,
                                    Optional<String> assinaturaAplicada, boolean jaAguardando,
                                    LongSupplier aguardando, long teto) {
        if (assinaturaAplicada.isPresent()) {
            return assinaturaAplicada.get().equals(evento.assinatura()) ? Desfecho.REPETICAO
                    : Desfecho.recusar(MotivoRecusa.REPETICAO_DIVERGENTE);
        }
        if (expectativa.isPresent()) {
            return creditoPara(evento.credito(), expectativa.get());
        }
        if (jaAguardando) {
            return Desfecho.REPETICAO;
        }
        return aguardando.getAsLong() < teto ? Desfecho.AGUARDAR : Desfecho.recusar(MotivoRecusa.AGUARDANDO_ESGOTADO);
    }

    private static Desfecho creditoPara(CreditoEsperado informado, Expectativa expectativa) {
        // Creditada sem deduplicação não ocorre (mesma transação); por segurança nunca credita duas vezes.
        if (expectativa.creditada()) {
            return Desfecho.recusar(MotivoRecusa.REPETICAO_DIVERGENTE);
        }
        return expectativa.credito().equals(informado) ? Desfecho.CREDITAR : Desfecho.recusar(MotivoRecusa.DIVERGENTE);
    }

    /**
     * Na transação que grava a expectativa: aplica o primeiro aguardando idêntico, na ordem de chegada recebida, e
     * recusa os demais do mesmo id — divergentes por conteúdo e idênticos por repetição com outra assinatura.
     */
    public static Resolucao resolver(CreditoEsperado esperado, List<LiquidacaoAguardando> fila) {
        Optional<LiquidacaoAguardando> aplicar = fila.stream().filter(a -> a.credito().equals(esperado)).findFirst();
        List<Recusa> recusar = fila.stream().filter(a -> aplicar.map(x -> x != a).orElse(true))
                .map(a -> new Recusa(a, a.credito().equals(esperado) ? MotivoRecusa.REPETICAO_DIVERGENTE
                        : MotivoRecusa.DIVERGENTE)).toList();
        return new Resolucao(aplicar, recusar);
    }
}
