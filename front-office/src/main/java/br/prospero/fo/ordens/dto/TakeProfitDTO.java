package br.prospero.fo.ordens.dto;

import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Perna de realização de lucro: Limit no preço alvo.")
public record TakeProfitDTO(@NotNull @Schema(required = true, examples = "11.00") String precoLimite) {
}
