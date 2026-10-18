package br.prospero.mo.mensageria.kafka;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Properties;

/** Cliente Kafka puro (ADR-012): produtor idempotente com acks=all; consumidor com commit manual. */
@ApplicationScoped
public class ConfiguracaoKafka {
    private final String servidores;
    private final String cliente;

    @Inject
    public ConfiguracaoKafka(@ConfigProperty(name = "kafka.bootstrap.servers") String servidores,
                             @ConfigProperty(name = "quarkus.application.name") String cliente) {
        this.servidores = servidores;
        this.cliente = cliente;
    }

    public Properties produtor() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servidores);
        p.put(ProducerConfig.CLIENT_ID_CONFIG, cliente + "-outbox");
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "10000");
        p.put(ProducerConfig.LINGER_MS_CONFIG, "0");
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return p;
    }

    public Properties consumidor(String grupo) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servidores);
        p.put(ConsumerConfig.CLIENT_ID_CONFIG, cliente + "-" + grupo);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, grupo);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "50");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        return p;
    }
}
