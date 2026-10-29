package br.prospero.fo.comum.dominio;

import java.util.Objects;

/**
 * Mensagem retirada do fluxo por erro permanente (ADR-019): posição no tópico, chave, traceparent e payload íntegros,
 * para reprocessar depois de corrigida a causa. O payload pode conter conta e ordem: só a API com escopo
 * {@code quarentena:reprocessar} o reaplica, e nenhuma rota o devolve.
 */
public record MensagemQuarentena(String topico, int particao, long deslocamento, String chave, String traceparent,
                                 byte[] payload, String motivo, int tentativas) {
    public static final int MOTIVO_MAXIMO = 120;
    public static final int PAYLOAD_MAXIMO = 1_048_576;

    public MensagemQuarentena {
        Objects.requireNonNull(topico);
        Objects.requireNonNull(motivo);
        if (particao < 0 || deslocamento < 0 || tentativas < 1) throw new IllegalArgumentException("Posição inválida.");
        motivo = motivo.length() > MOTIVO_MAXIMO ? motivo.substring(0, MOTIVO_MAXIMO) : motivo;
        // Acima do limite guarda só a posição: a mensagem continua identificável no tópico de origem.
        payload = payload == null || payload.length > PAYLOAD_MAXIMO ? null : payload.clone();
        chave = chave == null || chave.length() > 200 ? null : chave;
        traceparent = traceparent == null || traceparent.length() > 55 ? null : traceparent;
    }

    @Override
    public byte[] payload() { return payload == null ? null : payload.clone(); }
}
