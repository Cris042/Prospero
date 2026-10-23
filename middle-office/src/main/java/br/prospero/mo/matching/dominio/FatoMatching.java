package br.prospero.mo.matching.dominio;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fato de ordem do ME já validado pela fronteira (EnvelopeMatching.ler), com os campos que as guardas consultam.
 * Campos ausentes no tipo do fato ficam nulos/zero e nunca são lidos pelas guardas desse tipo; crie-o pelas fábricas
 * nomeadas por tipo, não pelo construtor posicional.
 */
public record FatoMatching(String tipo, UUID correlacao, String motivo, long executada, long cancelada, long restante,
                           long reduzida, BigDecimal preco, String lado, BigDecimal precoGatilho,
                           String tipoExecutavel) {

    public static FatoMatching stopPendente(UUID correlacao, BigDecimal precoGatilho) {
        return new FatoMatching("STOP_PENDENTE", correlacao, null, 0, 0, 0, 0, null, null, precoGatilho, null);
    }

    public static FatoMatching stopAcionado(UUID correlacao, String tipoExecutavel) {
        return new FatoMatching("STOP_ACIONADO", correlacao, null, 0, 0, 0, 0, null, null, null, tipoExecutavel);
    }

    public static FatoMatching ordemRejeitada(UUID correlacao, String motivo) {
        return new FatoMatching("ORDEM_REJEITADA", correlacao, motivo, 0, 0, 0, 0, null, null, null, null);
    }

    public static FatoMatching ordemEncerrada(UUID correlacao, String motivo, long executada, long cancelada) {
        return new FatoMatching("ORDEM_ENCERRADA", correlacao, motivo, executada, cancelada, 0, 0, null, null, null,
                null);
    }

    public static FatoMatching ofertaNoLivro(UUID correlacao, long restante, BigDecimal preco, String lado) {
        return new FatoMatching("OFERTA_NO_LIVRO", correlacao, null, 0, 0, restante, 0, preco, lado, null, null);
    }

    public static FatoMatching ordemReduzida(UUID correlacao, long reduzida, long restante) {
        return new FatoMatching("ORDEM_REDUZIDA", correlacao, null, 0, 0, restante, reduzida, null, null, null, null);
    }

    /** Fato cujos dados as guardas não consultam (ex.: ORDEM_ACEITA). */
    public static FatoMatching semCampos(String tipo, UUID correlacao) {
        return new FatoMatching(tipo, correlacao, null, 0, 0, 0, 0, null, null, null, null);
    }
}
