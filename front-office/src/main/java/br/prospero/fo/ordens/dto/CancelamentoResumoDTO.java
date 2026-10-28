package br.prospero.fo.ordens.dto;

import br.prospero.fo.ordens.dominio.OperacaoCancelamento;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Resumo do cancelamento manual embutido na ordem; null quando não há pedido.")
public record CancelamentoResumoDTO(
        @Schema(enumeration = {"AGUARDANDO_RISCO", "SOLICITADO", "DECIDIDO", "CONFIRMADO", "RECUSADO"}) String estado,
        @Schema(nullable = true) String decisao,
        @Schema(nullable = true) String motivo) {

    public static CancelamentoResumoDTO de(OperacaoCancelamento o) {
        return new CancelamentoResumoDTO(o.estado().name(), o.decisao() == null ? null : o.decisao().name(), o.motivo());
    }
}
