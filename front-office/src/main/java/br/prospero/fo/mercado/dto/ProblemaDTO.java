package br.prospero.fo.mercado.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Problem Details (RFC 9457) com código estável.")
public record ProblemaDTO(String type, String title, int status, String codigo, String detail) {
}
