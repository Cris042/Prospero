package br.prospero.fo.ordens.dto;

import br.prospero.fo.ordens.dominio.MarcoTrajetoria;
import java.time.Instant;
import java.util.UUID;

public record MarcoTrajetoriaDTO(String sequencia, String origem, String tipo, UUID evento, UUID causacao,
                                String negocio, Instant ocorridoEm, Instant observadoEm, String quantidade,
                                String motivo, String traceId) {
    public static MarcoTrajetoriaDTO de(MarcoTrajetoria marco) {
        return new MarcoTrajetoriaDTO(Long.toString(marco.sequencia()), marco.origem(), marco.tipo(), marco.evento(),
                marco.causacao(), marco.negocio(), marco.ocorridoEm(), marco.observadoEm(), marco.quantidade(),
                marco.motivo(), marco.traceId());
    }
}
