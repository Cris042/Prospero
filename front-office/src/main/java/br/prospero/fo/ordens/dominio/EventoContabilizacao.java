package br.prospero.fo.ordens.dominio;

import java.time.Instant;
import java.util.UUID;

public record EventoContabilizacao(UUID id, UUID causacao, String negocio, String ticker, long quantidade,
                                  UUID ordemCompra, String contaCompra, UUID ordemVenda, String contaVenda,
                                  Instant ocorridoEm, String traceparent, String assinatura) {
}
