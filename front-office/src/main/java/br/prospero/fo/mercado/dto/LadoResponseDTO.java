package br.prospero.fo.mercado.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Até cinco níveis exibidos; totais incluem todos os níveis conhecidos.")
public record LadoResponseDTO(List<NivelResponseDTO> niveis, int niveisTotal, long quantidadeTotal) {
}
