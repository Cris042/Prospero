package br.prospero.fo.ordens.dto;

import br.prospero.fo.ordens.dominio.TipoOrdem;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Perna de proteção: STOP (gatilho) ou STOP_LIMIT (gatilho e limite).")
public record StopLossDTO(
        @NotNull @Schema(required = true, enumeration = {"STOP", "STOP_LIMIT"}) TipoOrdem tipo,
        @NotNull @Schema(required = true, examples = "9.50") String precoGatilho,
        @Schema(nullable = true, examples = "9.45") String precoLimite) {
}
