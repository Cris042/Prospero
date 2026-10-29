package br.prospero.mo.mensageria.dto;

import br.prospero.mo.mensageria.dominio.ItemQuarentena;
import br.prospero.mo.mensageria.dominio.ResolucaoQuarentena;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Desfecho da mensagem; repetido=true quando ela já estava resolvida antes desta chamada.")
public record ResolucaoQuarentenaDTO(long id, @Schema(enumeration = {"REPROCESSADA", "DESCARTADA"}) String resolucao,
                                     boolean repetido) {
    public static ResolucaoQuarentenaDTO nova(long id, ResolucaoQuarentena resolucao) {
        return new ResolucaoQuarentenaDTO(id, resolucao.name(), false);
    }

    public static ResolucaoQuarentenaDTO repetida(ItemQuarentena item) {
        return new ResolucaoQuarentenaDTO(item.id(), item.resolucao().name(), true);
    }
}
