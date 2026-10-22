package br.prospero.fo.cotacao.kafka;

import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Posição só é considerada após aplicar todos os registros e confirmar; invalida em falha e rebalance. */
public final class CicloCotacao {
    private final Consumer<String, byte[]> consumidor;
    private final java.util.function.Consumer<EventoMatching> aplicar;
    private volatile boolean emDia;
    private volatile long verificadoEm;
    private int particaoFalha = -1;
    private long offsetFalha = -1;

    public CicloCotacao(Consumer<String, byte[]> consumidor, java.util.function.Consumer<EventoMatching> aplicar) {
        this.consumidor = consumidor;
        this.aplicar = aplicar;
    }

    public void iniciar() {
        consumidor.subscribe(List.of(EnvelopeMatching.TOPICO), new ConsumerRebalanceListener() {
            @Override public void onPartitionsRevoked(Collection<TopicPartition> particoes) { invalidar(); }
            @Override public void onPartitionsAssigned(Collection<TopicPartition> particoes) { invalidar(); }
            @Override public void onPartitionsLost(Collection<TopicPartition> particoes) { invalidar(); }
        });
    }

    public void rodada() {
        try { processarRodada(); }
        catch (RuntimeException erro) { invalidar(); throw erro; }
    }

    private void processarRodada() {
        var registros = consumidor.poll(Duration.ofMillis(500));
        if (!registros.isEmpty()) invalidar();
        for (var registro : registros) {
            particaoFalha = registro.partition();
            offsetFalha = registro.offset();
            var particao = new TopicPartition(registro.topic(), registro.partition());
            try {
                aplicar.accept(EnvelopeMatching.evento(registro.value(), registro.key()));
                consumidor.commitSync(Map.of(particao, new OffsetAndMetadata(registro.offset() + 1)));
            } catch (RuntimeException erro) {
                // O poll já avançou a posição: não a usar como prova de persistência e repetir a partir da falha.
                if (consumidor.assignment().contains(particao)) consumidor.seek(particao, registro.offset());
                throw erro;
            }
        }
        var particoes = consumidor.assignment();
        if (particoes.isEmpty()) return;
        var fins = consumidor.endOffsets(particoes, Duration.ofSeconds(5));
        boolean alcancou = true;
        for (var particao : particoes) {
            if (consumidor.position(particao, Duration.ofSeconds(5)) < fins.get(particao)) alcancou = false;
        }
        verificadoEm = System.nanoTime();
        emDia = alcancou;
    }

    public boolean emDia() {
        return emDia && System.nanoTime() - verificadoEm < Duration.ofSeconds(10).toNanos();
    }

    public int particaoFalha() { return particaoFalha; }
    public long offsetFalha() { return offsetFalha; }

    public void invalidar() { emDia = false; }
}
