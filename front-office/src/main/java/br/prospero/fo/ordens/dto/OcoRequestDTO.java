package br.prospero.fo.ordens.dto;

import br.prospero.fo.ordens.dominio.LadoOrdem;
import br.prospero.fo.ordens.dominio.Validade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Grupo OCO de proteção: duas pernas com mesmo ativo, lado e quantidade, validade DIA. Posição é verificada na RF04.")
public record OcoRequestDTO(
        @NotNull @Size(max = 8) @Schema(required = true, examples = "ALFA3", maxLength = 8) String ticker,
        @NotNull @Schema(required = true, description = "VENDA protege posição comprada; COMPRA cobre vendida.") LadoOrdem lado,
        @NotNull @Schema(required = true, examples = "100") Long quantidade,
        @NotNull @Schema(required = true, enumeration = {"DIA"}) Validade validade,
        @NotNull @Valid @Schema(required = true) TakeProfitDTO takeProfit,
        @NotNull @Valid @Schema(required = true) StopLossDTO stopLoss) {
}
