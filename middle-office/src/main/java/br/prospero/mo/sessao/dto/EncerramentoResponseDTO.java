package br.prospero.mo.sessao.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Encerramento aceito: o comando foi registrado na outbox; 202 não comprova a expiração no ME.")
public record EncerramentoResponseDTO(String ticker, LocalDate dataSessao,
                                      @Schema(description = "Id determinístico do comando (causação dos eventos v4).") UUID comando,
                                      @Schema(description = "true se o mesmo encerramento já estava registrado.") boolean repetido) {
}
