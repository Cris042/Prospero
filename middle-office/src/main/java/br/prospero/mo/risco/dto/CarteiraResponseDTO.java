package br.prospero.mo.risco.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Recursos de risco da conta do token (RF04): disponível + reservado = total. Valores em texto decimal.")
public record CarteiraResponseDTO(String conta, String saldoTotal, String saldoDisponivel, String saldoReservado,
                                  List<CustodiaResponseDTO> custodias,
                                  @Schema(description = "Venda a descoberto aberta × preço de referência (ADR-021).")
                                  String exposicaoVendida,
                                  @Schema(description = "Limite de exposição vendida da conta; 0 desliga a venda a descoberto.")
                                  String limiteVendido) {
    @Schema(description = "total/disponível/reservado cobrem só a custódia possuída; a custódia efetiva é total − descobertoExecutado.")
    public record CustodiaResponseDTO(String ticker, long total, long disponivel, long reservado,
                                      @Schema(description = "Venda a descoberto reservada e ainda não executada.")
                                      long descobertoReservado,
                                      @Schema(description = "Venda a descoberto executada e ainda não recomprada (custódia a entregar).")
                                      long descobertoExecutado) {
    }
}
