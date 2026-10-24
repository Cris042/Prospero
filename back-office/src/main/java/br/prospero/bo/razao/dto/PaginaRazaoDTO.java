package br.prospero.bo.razao.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Partidas da conta do token em partidas dobradas, mais recentes primeiro. A contraparte é a Câmara "
        + "simulada (CAMARA), sem identificar a outra conta.")
public record PaginaRazaoDTO(String conta, List<LancamentoDTO> lancamentos,
                             @Schema(nullable = true, description = "Cursor opaco da próxima página; null no fim.")
                             String proximoCursor) {
    public record LancamentoDTO(@Schema(description = "Transação contábil do lado; débitos = créditos por unidade.")
                                String transacao, String negocio, String ticker,
                                @Schema(enumeration = {"COMPRADOR", "VENDEDOR"}) String lado,
                                @Schema(enumeration = {"D0", "D2"}) String momento, String data,
                                @Schema(enumeration = {"CAIXA", "CAIXA_A_RECEBER", "CUSTODIA", "CUSTODIA_A_RECEBER", "CAMARA"})
                                String contaContabil,
                                @Schema(enumeration = {"DEBITO", "CREDITO"}) String natureza,
                                @Schema(description = "BRL ou ticker do ativo.") String unidade,
                                @Schema(description = "BRL com duas casas; ativo em quantidade inteira.") String valor) { }
}
