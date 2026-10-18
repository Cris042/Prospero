package br.prospero.fo.comum.kafka;

import br.prospero.fo.comum.dominio.MensagemInvalidaException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Laço de consumo com commit manual depois do efeito local (ADR-012). Mensagem inválida é registrada e o offset avança;
 * falha transitória reposiciona as partições no primeiro registro não confirmado e repete após uma pausa.
 */
public final class ConsumoKafka implements Runnable {
    private static final Logger LOG = Logger.getLogger(ConsumoKafka.class);
    private final Properties propriedades;
    private final String topico;
    private final Processador processador;
    private final Rejeicao rejeicao;
    private volatile boolean ativo = true;
    private volatile KafkaConsumer<String, byte[]> consumidor;

    /** Efeito local de um registro, numa transação própria. */
    @FunctionalInterface
    public interface Processador {
        void processar(ConsumerRecord<String, byte[]> registro);
    }

    @FunctionalInterface
    public interface Rejeicao {
        void registrar(String topico, int particao, long deslocamento, String motivo);
    }

    public ConsumoKafka(Properties propriedades, String topico, Processador processador, Rejeicao rejeicao) {
        this.propriedades = propriedades;
        this.topico = topico;
        this.processador = processador;
        this.rejeicao = rejeicao;
    }

    @Override
    public void run() {
        try (KafkaConsumer<String, byte[]> c = new KafkaConsumer<>(propriedades)) {
            consumidor = c;
            c.subscribe(List.of(topico));
            while (ativo) {
                try {
                    ciclo(c);
                } catch (WakeupException parada) {
                    break;
                } catch (RuntimeException falha) {
                    LOG.warnf("Consumo de %s falhou; repetindo: %s", topico, falha.getClass().getSimpleName());
                    pausar();
                }
            }
        }
    }

    public void parar() {
        ativo = false;
        KafkaConsumer<String, byte[]> c = consumidor;
        if (c != null) {
            c.wakeup();
        }
    }

    private void ciclo(KafkaConsumer<String, byte[]> c) {
        ConsumerRecords<String, byte[]> registros = c.poll(Duration.ofMillis(500));
        List<TopicPartition> particoes = new ArrayList<>(registros.partitions());
        Map<TopicPartition, OffsetAndMetadata> confirmados = new HashMap<>();
        for (int i = 0; i < particoes.size(); i++) {
            TopicPartition particao = particoes.get(i);
            for (ConsumerRecord<String, byte[]> registro : registros.records(particao)) {
                try {
                    processarOuRejeitar(registro);
                } catch (RuntimeException falha) {
                    if (!confirmados.isEmpty()) {
                        c.commitSync(confirmados);
                    }
                    c.seek(particao, registro.offset());
                    for (TopicPartition restante : particoes.subList(i + 1, particoes.size())) {
                        c.seek(restante, registros.records(restante).getFirst().offset());
                    }
                    throw falha;
                }
                confirmados.put(particao, new OffsetAndMetadata(registro.offset() + 1));
            }
        }
        if (!confirmados.isEmpty()) {
            c.commitSync(confirmados);
        }
    }

    private void processarOuRejeitar(ConsumerRecord<String, byte[]> registro) {
        try {
            processador.processar(registro);
        } catch (MensagemInvalidaException invalida) {
            LOG.warnf("Mensagem inválida descartada: topico=%s particao=%d offset=%d motivo=%s", registro.topic(),
                    registro.partition(), registro.offset(), invalida.getMessage());
            rejeicao.registrar(registro.topic(), registro.partition(), registro.offset(), invalida.getMessage());
        }
    }

    private void pausar() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            ativo = false;
        }
    }
}
