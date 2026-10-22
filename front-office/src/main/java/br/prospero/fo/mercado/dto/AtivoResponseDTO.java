package br.prospero.fo.mercado.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;

@Schema(description = "Resumo do ativo. Decimais são textuais; valores desconhecidos são null, nunca zero.")
public record AtivoResponseDTO(
        @Schema(examples = "ALFA3") String ticker,
        String nome,
        @Schema(examples = "10.00") String precoReferencia,
        @Schema(examples = "0.01") String tick,
        long lote,
        @Schema(nullable = true) String ultimoPreco,
        @Schema(nullable = true, description = "Último menos referência.") String variacao,
        @Schema(nullable = true, description = "Percentual sobre a referência, duas casas HALF_EVEN.") String variacaoPercentual,
        @Schema(nullable = true) String melhorCompra,
        @Schema(nullable = true) String melhorVenda,
        @Schema(nullable = true) String spread,
        @Schema(nullable = true, description = "Quantidade negociada na sessão.") Long volumeQuantidade,
        @Schema(nullable = true, description = "Soma de preço × quantidade na sessão.") String volumeFinanceiro,
        @Schema(enumeration = {"SEM_DADOS", "VAZIO", "SOMENTE_COMPRA", "SOMENTE_VENDA", "COMPLETO"}) String estadoLivro,
        @Schema(nullable = true) LocalDate sessao,
        @Schema(nullable = true) Long revisao,
        @Schema(nullable = true) Instant preparadoEm,
        @Schema(description = "MATCHING: consumidor atrasado, sessão anterior ou encerrada; sintético: idade máxima ou sessão anterior.") boolean defasado,
        @Schema(nullable = true, enumeration = {"PREPARACAO_SINTETICA", "MATCHING"}) String origem,
        @Schema(nullable = true, description = "VWAP da sessão; quatro casas HALF_EVEN.") String vwap) {
    public AtivoResponseDTO(String ticker, String nome, String precoReferencia, String tick, long lote,
                            String ultimoPreco, String variacao, String variacaoPercentual, String melhorCompra,
                            String melhorVenda, String spread, Long volumeQuantidade, String volumeFinanceiro,
                            String estadoLivro, LocalDate sessao, Long revisao, Instant preparadoEm, boolean defasado, String origem) {
        this(ticker, nome, precoReferencia, tick, lote, ultimoPreco, variacao, variacaoPercentual, melhorCompra,
                melhorVenda, spread, volumeQuantidade, volumeFinanceiro, estadoLivro, sessao, revisao, preparadoEm, defasado, origem, null);
    }
}
