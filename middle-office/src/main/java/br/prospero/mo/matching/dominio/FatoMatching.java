package br.prospero.mo.matching.dominio;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fato de ordem do ME já validado pela fronteira (EnvelopeMatching.ler), com os campos que as guardas consultam.
 * Campos ausentes no tipo do fato ficam nulos/zero e nunca são lidos pelas guardas desse tipo.
 */
public record FatoMatching(String tipo, UUID correlacao, String motivo, long executada, long cancelada, long restante,
                           long reduzida, BigDecimal preco, String lado, BigDecimal precoGatilho,
                           String tipoExecutavel) {
}
