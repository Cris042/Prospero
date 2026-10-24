package br.prospero.bo.razao.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Negócios da conta do token, mais recentes primeiro; um item por lado (autonegociação traz os dois).")
public record PaginaNegociosDTO(String conta, List<NegocioDTO> negocios,
                                @Schema(nullable = true, description = "Cursor opaco da próxima página; null no fim.")
                                String proximoCursor) {
    @Schema(description = "Lado da própria conta; sem contraparte. Valores textuais: preço/valor BRL com duas casas.")
    public record NegocioDTO(String negocio, String ticker,
                             @Schema(enumeration = {"COMPRADOR", "VENDEDOR"}) String lado,
                             @Schema(description = "Ordem da própria conta neste lado.") String ordem,
                             String dataSessao, String dataLiquidacao, String preco, String quantidade, String valor,
                             @Schema(enumeration = {"PENDENTE", "LIQUIDADO"}) String liquidacao) { }
}
