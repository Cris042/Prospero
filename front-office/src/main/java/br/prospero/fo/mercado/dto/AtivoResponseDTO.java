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
        @Schema(description = "Snapshot mais antigo que a idade máxima configurada.") boolean defasado,
        @Schema(nullable = true, enumeration = {"PREPARACAO_SINTETICA"}) String origem) {
}
