package br.prospero.fo.ordens.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Ordem registrada e validada (formato, lote, tick, túnel). RECEBIDA não significa reserva ou execução.")
public record OrdemResponseDTO(
        UUID id, String ticker, String lado, String tipo, String validade, long quantidade,
        @Schema(nullable = true) String precoLimite,
        @Schema(nullable = true) String precoGatilho,
        @Schema(nullable = true) UUID grupoOco,
        @Schema(nullable = true, enumeration = {"TAKE_PROFIT", "STOP_LOSS"}) String papelOco,
        @Schema(enumeration = {"RECEBIDA"}) String estado,
        @Schema(description = "Sessão da validade DIA (America/Sao_Paulo).") LocalDate dataSessao,
        Instant recebidaEm,
        @Schema(nullable = true, description = "Base do túnel aplicada no registro; null para Market e ordens anteriores à RF03.") String precoBaseTunel,
        @Schema(nullable = true, enumeration = {"ULTIMO", "REFERENCIA"}) String origemBase) {
}
