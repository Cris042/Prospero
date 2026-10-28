package br.prospero.fo.ordens.dto;

import br.prospero.fo.ordens.dominio.OperacaoCancelamento;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Operação de cancelamento manual da ordem. Pedido aceito não implica cancelamento: o estado só vira "
        + "CONFIRMADO depois do resultado do middle-office e a liberação do saldo é confirmada por ele.")
public record CancelamentoResponseDTO(
        UUID ordem,
        @Schema(enumeration = {"AGUARDANDO_RISCO", "SOLICITADO", "DECIDIDO", "CONFIRMADO", "RECUSADO"}) String estado,
        @Schema(nullable = true, description = "Resultado do matching para o comando.",
                enumeration = {"APLICADO", "SEM_SALDO_VIVO", "ALVO_DIVERGENTE", "ORIGEM_OCO"}) String decisao,
        @Schema(nullable = true, description = "Motivo da recusa; somente em RECUSADO.", enumeration = {
                "SEM_SALDO_VIVO", "ALVO_DIVERGENTE", "ORIGEM_OCO", "ADMISSAO_NAO_COMPROVADA", "RISCO_REJEITADO"}) String motivo,
        Instant solicitadaEm, Instant atualizadaEm) {

    public static CancelamentoResponseDTO de(OperacaoCancelamento o) {
        return new CancelamentoResponseDTO(o.ordem(), o.estado().name(), o.decisao() == null ? null : o.decisao().name(),
                o.motivo(), o.solicitadaEm(), o.atualizadaEm());
    }
}
