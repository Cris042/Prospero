package br.prospero.mo.mensageria.dto;

import br.prospero.mo.mensageria.dominio.ItemQuarentena;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Mensagem em quarentena, sem payload: posição no tópico, motivo e tentativas.")
public record QuarentenaItemDTO(long id, String topico, int particao, long offset,
                                @Schema(description = "Motivo do erro permanente, sem conteúdo da mensagem.") String motivo,
                                int tentativas, Instant quarentenadoEm,
                                @Schema(description = "false para linhas anteriores à V02, que só podem ser descartadas.")
                                boolean reprocessavel) {
    public static QuarentenaItemDTO de(ItemQuarentena item) {
        var m = item.mensagem();
        return new QuarentenaItemDTO(item.id(), m.topico(), m.particao(), m.deslocamento(), m.motivo(), m.tentativas(),
                item.quarentenadoEm(), m.payload() != null);
    }
}
