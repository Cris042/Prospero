package br.prospero.mo.mensageria.kafka;

import org.apache.kafka.clients.consumer.Consumer;

/** Acesso de teste a um ciclo do laço de consumo, para ITs de outros pacotes com MockConsumer. */
public final class CicloConsumo {
    private CicloConsumo() {
    }

    public static void ciclo(ConsumoKafka consumo, Consumer<String, byte[]> consumidor) {
        consumo.ciclo(consumidor);
    }
}
