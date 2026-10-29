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

    /** Envio que devolve o ack pendente: o produtor idempotente preserva a ordem por partição entre envios em voo. */
    @FunctionalInterface
    public interface EnvioAssincrono {
        Ack enviar(byte[] evento) throws Exception;
    }

    @FunctionalInterface
    public interface Ack {
        void aguardar() throws Exception;
    }

    private PublicacaoMatching() {
    }

    public static void publicar(List<byte[]> eventos, Envio envio, Confirmacao confirmacao) throws Exception {
        publicarEmLote(eventos, evento -> {
            envio.enviar(evento);
            return () -> { };
        }, confirmacao);
    }

    /**
     * V02-6 (RNF04): despacha todos os eventos do comando antes de esperar os acks, em vez de um ida-e-volta por evento.
     * O checkpoint continua só depois do último ack; qualquer falha propaga sem confirmar, e o replay republica os
     * mesmos bytes e IDs (ADR-013).
     */
    public static void publicarEmLote(List<byte[]> eventos, EnvioAssincrono envio, Confirmacao confirmacao) throws Exception {
        List<Ack> acks = new java.util.ArrayList<>(eventos.size());
        for (byte[] evento : eventos) {
            acks.add(envio.enviar(evento));
        }
        for (Ack ack : acks) {
            ack.aguardar();
        }
        confirmacao.confirmar();
    }
}
