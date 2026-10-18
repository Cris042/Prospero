package br.prospero.fo.ordens.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Grupo OCO registrado atomicamente: TAKE_PROFIT e STOP_LOSS.")
public record OcoResponseDTO(UUID grupoOco, List<OrdemResponseDTO> ordens) {
}
