package br.prospero.me.matching.kafka;

import java.util.List;

/** Fronteira testável entre acks do conjunto inteiro e confirmação do comando (ADR-013). */
public final class PublicacaoMatching {
    @FunctionalInterface
    public interface Envio {
        void enviar(byte[] evento) throws Exception;
    }

    @FunctionalInterface
    public interface Confirmacao {
        void confirmar() throws Exception;
    }

    private PublicacaoMatching() {
    }

    public static void publicar(List<byte[]> eventos, Envio envio, Confirmacao confirmacao) throws Exception {
        for (byte[] evento : eventos) {
            envio.enviar(evento);
        }
        confirmacao.confirmar();
    }
}
