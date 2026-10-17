package br.prospero.fo.mercado.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Schema(description = "Snapshot sintético integral de um ativo; substitui o anterior atomicamente.")
public record SnapshotRequestDTO(
        @NotNull @Schema(required = true, examples = "2026-10-17", description = "Sessão (data America/Sao_Paulo).") LocalDate sessao,
        @NotNull @Size(max = 200) @Schema(required = true) List<@NotNull @Valid OfertaRequestDTO> ofertas,
        @NotNull @Size(max = 200) @Schema(required = true) List<@NotNull @Valid NegocioRequestDTO> negocios) {
}
