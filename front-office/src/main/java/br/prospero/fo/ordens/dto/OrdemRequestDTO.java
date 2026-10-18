package br.prospero.fo.ordens.dto;

import br.prospero.fo.ordens.dominio.LadoOrdem;
import br.prospero.fo.ordens.dominio.TipoOrdem;
import br.prospero.fo.ordens.dominio.Validade;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Ordem simples. A conta vem do token; preços conforme o tipo (Limit: limite; Stop: gatilho; Stop Limit: ambos).")
public record OrdemRequestDTO(
        @NotNull @Size(max = 8) @Schema(required = true, examples = "ALFA3", maxLength = 8) String ticker,
        @NotNull @Schema(required = true) LadoOrdem lado,
        @NotNull @Schema(required = true) TipoOrdem tipo,
        @NotNull @Schema(required = true, description = "Market: IOC/FOK; Limit: DIA/IOC/FOK; Stop e Stop Limit: DIA.") Validade validade,
        @NotNull @Schema(required = true, examples = "100") Long quantidade,
        @Schema(nullable = true, examples = "10.25", description = "Decimal textual.") String precoLimite,
        @Schema(nullable = true, examples = "9.80", description = "Decimal textual.") String precoGatilho) {
}
