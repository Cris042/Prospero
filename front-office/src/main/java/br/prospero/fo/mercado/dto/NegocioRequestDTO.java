package br.prospero.fo.mercado.dto;

import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Negócio sintético da sessão; o último da lista define o último preço.")
public record NegocioRequestDTO(
        @NotNull @Schema(required = true, examples = "10.05") String preco,
        @NotNull @Schema(required = true, examples = "200") Long quantidade) {
}
