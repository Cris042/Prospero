package br.prospero.fo.comum.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Problem Details (RFC 9457) com código estável.")
public record ProblemaDTO(String type, String title, int status, String codigo, String detail,
                          @Schema(description = "Ocorrência: caminho da requisição.") String instance,
                          @Schema(description = "Identificador de rastreio (X-Request-ID).") String correlacao) {
}
