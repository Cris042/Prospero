package br.prospero.bo.liquidacao;

import br.prospero.bo.liquidacao.kafka.ConfiguracaoKafka;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ConfiguracaoKafkaTest {
    @Test
    void produtorConstroiComConfiguracaoRealIdempotenteEAcksAllSemBroker() {
        var propriedades = new ConfiguracaoKafka("localhost:1", "back-office").produtor();
        assertEquals("back-office-outbox", propriedades.get(ProducerConfig.CLIENT_ID_CONFIG));
        assertEquals("all", propriedades.get(ProducerConfig.ACKS_CONFIG));
        assertEquals("true", propriedades.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
        assertDoesNotThrow(() -> {
            try (var produtor = new KafkaProducer<String, byte[]>(propriedades)) {
                produtor.close(Duration.ZERO);
            }
        });
    }
}
