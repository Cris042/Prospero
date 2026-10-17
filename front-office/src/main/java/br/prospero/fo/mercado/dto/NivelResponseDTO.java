package br.prospero.fo.mercado.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Nível agregado por preço.")
public record NivelResponseDTO(String preco, long quantidade, int ofertas, long quantidadeAcumulada) {
}
