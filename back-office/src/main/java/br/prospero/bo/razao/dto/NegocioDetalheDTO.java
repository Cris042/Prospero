package br.prospero.bo.razao.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Negócio de que a conta participa, com somente os próprios lados.")
public record NegocioDetalheDTO(String conta, String negocio, String ticker, String dataSessao, String dataLiquidacao,
                                String preco, String quantidade, String valor, List<LadoDTO> lados) {
    public record LadoDTO(@Schema(enumeration = {"COMPRADOR", "VENDEDOR"}) String lado, String ordem,
                          @Schema(enumeration = {"PENDENTE", "LIQUIDADO"}) String liquidacao) { }
}
