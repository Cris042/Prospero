package br.prospero.bo.liquidacao.kafka;

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

}
