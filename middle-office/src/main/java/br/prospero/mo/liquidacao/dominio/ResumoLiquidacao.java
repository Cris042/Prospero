package br.prospero.mo.liquidacao.dominio;

import java.util.Map;

/** Agregados para métricas de labels finitos: sem conta, valor, preço ou id. */
public record ResumoLiquidacao(long aguardando, long idadeMaisAntigoSegundos, long pendentes,
                               Map<MotivoRecusa, Long> recusadas) {
    public ResumoLiquidacao {
        recusadas = Map.copyOf(recusadas);
    }
}
