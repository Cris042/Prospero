package br.prospero.mo.risco.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Map;

@Schema(description = "Carteira sintética de teste: saldo em texto decimal (até duas casas) e custódia por ativo; ativos "
        + "ausentes ficam com zero.")
public record PreparacaoCarteiraRequestDTO(
        @NotNull @Schema(examples = "100000.00") String saldo,
        @Size(max = 10) Map<String, Long> custodia) {
}
