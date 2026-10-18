package br.prospero.fo.ordens.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Ordens da própria conta, mais recentes primeiro.")
public record PaginaOrdensDTO(List<OrdemResponseDTO> ordens,
                              @Schema(nullable = true, description = "Cursor opaco para a próxima página.") String proximoCursor) {
}
