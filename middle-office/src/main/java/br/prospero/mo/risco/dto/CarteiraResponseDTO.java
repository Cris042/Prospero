package br.prospero.mo.risco.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Recursos de risco da conta do token (RF04): disponível + reservado = total. Valores em texto decimal.")
public record CarteiraResponseDTO(String conta, String saldoTotal, String saldoDisponivel, String saldoReservado,
                                  List<CustodiaResponseDTO> custodias) {
    public record CustodiaResponseDTO(String ticker, long total, long disponivel, long reservado) {
    }
}
