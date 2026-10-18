package br.prospero.fo.ordens.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Ordem registrada. RECEBIDA significa apenas registro: sem validação de preço, reserva ou execução.")
public record OrdemResponseDTO(
        UUID id, String ticker, String lado, String tipo, String validade, long quantidade,
        @Schema(nullable = true) String precoLimite,
        @Schema(nullable = true) String precoGatilho,
        @Schema(nullable = true) UUID grupoOco,
        @Schema(nullable = true, enumeration = {"TAKE_PROFIT", "STOP_LOSS"}) String papelOco,
        @Schema(enumeration = {"RECEBIDA"}) String estado,
        @Schema(description = "Sessão da validade DIA (America/Sao_Paulo).") LocalDate dataSessao,
        Instant recebidaEm) {
}
