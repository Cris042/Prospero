package br.prospero.fo.ordens.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaginaTrajetoriaDTO(UUID ordem, String conta, String revisao, boolean historicoCompleto,
                                 Instant disponivelDesde, List<MarcoTrajetoriaDTO> eventos, String proximoCursor) {
}
