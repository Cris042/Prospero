package br.prospero.me.matching.kafka;

import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ConfiguracaoKafkaTest {
    @Test
    @SuppressWarnings("unchecked")
    void adminAceitaPrazosConfiguradosSemDependerDeBroker() {
        Instance<AgroalDataSource> fonte = (Instance<AgroalDataSource>) Proxy.newProxyInstance(
                Instance.class.getClassLoader(), new Class<?>[]{Instance.class}, (proxy, metodo, argumentos) -> {
                    throw new AssertionError("Configuração Kafka não deve consultar o banco.");
                });
        SimpleMeterRegistry metricas = new SimpleMeterRegistry();
        try {
            ProcessadorMatching processador = new ProcessadorMatching(fonte, metricas, "127.0.0.1:1", true);
            assertDoesNotThrow(() -> {
                Admin admin = Admin.create(processador.adminConfig());
                admin.close(Duration.ZERO);
            });
            // V02-6: sem espera de lote no produtor de eventos; acks=all e idempotência continuam.
            var produtor = processador.produtorConfig();
            org.junit.jupiter.api.Assertions.assertEquals("0", produtor.get("linger.ms"));
            org.junit.jupiter.api.Assertions.assertEquals("all", produtor.get("acks"));
            org.junit.jupiter.api.Assertions.assertEquals("true", produtor.get("enable.idempotence"));
        } finally {
            metricas.close();
        }
    }
}
