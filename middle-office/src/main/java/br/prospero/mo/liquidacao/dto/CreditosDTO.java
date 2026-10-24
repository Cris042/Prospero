package br.prospero.mo.liquidacao.dto;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description="Créditos da própria conta para um negócio, no máximo dois lados; ausência ainda não comprova consumo.")
public record CreditosDTO(String conta, List<CreditoDTO> creditos) {
    public record CreditoDTO(String negocio, String lado, String ticker, String recurso, String valor, boolean creditado) { }
}
