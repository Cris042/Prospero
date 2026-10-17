package br.prospero.fo.mercado.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Resultado da preparação sintética.")
public record PreparacaoResponseDTO(UUID namespace, String ticker, long revisao, Instant preparadoEm) {
}
