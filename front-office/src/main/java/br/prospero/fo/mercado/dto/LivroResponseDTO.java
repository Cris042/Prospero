package br.prospero.fo.mercado.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Livro agregado do ativo: compras decrescentes, vendas crescentes. Sem snapshot, lados e metadados são null.")
public record LivroResponseDTO(String ticker, UUID namespace, String estadoLivro,
                               @Schema(nullable = true) LadoResponseDTO compra,
                               @Schema(nullable = true) LadoResponseDTO venda,
                               @Schema(nullable = true) Long revisao,
                               @Schema(nullable = true) LocalDate sessao,
                               @Schema(nullable = true) Instant preparadoEm,
                               @Schema(description = "Snapshot de sessão anterior ou mais antigo que a idade máxima.") boolean defasado,
                               @Schema(nullable = true, enumeration = {"PREPARACAO_SINTETICA"}) String origem) {
}
