package br.prospero.fo.mercado.dto;

import br.prospero.fo.mercado.dominio.Lado;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Oferta individual em repouso; agregada por preço na consulta.")
public record OfertaRequestDTO(
        @NotNull @Schema(required = true) Lado lado,
        @NotNull @Schema(required = true, examples = "9.98", description = "Decimal textual, múltiplo do tick.") String preco,
        @NotNull @Schema(required = true, examples = "100", description = "Inteira, múltipla do lote.") Long quantidade) {
}
