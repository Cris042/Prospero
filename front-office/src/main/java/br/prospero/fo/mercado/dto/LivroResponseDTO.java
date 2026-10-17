package br.prospero.fo.mercado.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Livro agregado do ativo: compras decrescentes, vendas crescentes.")
public record LivroResponseDTO(String ticker, UUID namespace, String estadoLivro, LadoResponseDTO compra,
                               LadoResponseDTO venda, @Schema(nullable = true) Long revisao,
                               @Schema(nullable = true) Instant preparadoEm, boolean defasado) {
}
