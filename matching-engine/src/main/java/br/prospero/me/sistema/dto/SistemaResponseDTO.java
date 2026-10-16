package br.prospero.me.sistema.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Identidade técnica; não representa prontidão de negociação.")
public record SistemaResponseDTO(
        @Schema(examples = "matching-engine") String servico,
        @Schema(examples = "0.1.0-SNAPSHOT") String versao,
        @Schema(examples = "FUNDAÇÃO") String etapa) {
}
