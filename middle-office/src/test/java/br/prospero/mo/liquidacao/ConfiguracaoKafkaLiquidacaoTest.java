package br.prospero.mo.liquidacao;

import br.prospero.mo.liquidacao.kafka.ConsumidorLiquidacao;
import br.prospero.mo.mensageria.kafka.ConfiguracaoKafka;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lição da RF07: configuração recusada pelo cliente Kafka só aparece ao construí-lo e matava o consumo. O consumidor de
 * liquidação é construído com a configuração real do serviço, sem broker (construção não conecta).
 */
class ConfiguracaoKafkaLiquidacaoTest {
    @Test
    void consumidorDeLiquidacaoConstroiComConfiguracaoRealEGrupoProprio() {
        var propriedades = new ConfiguracaoKafka("localhost:1", "middle-office").consumidor(ConsumidorLiquidacao.GRUPO);
        assertEquals("middle-office-liquidacao", propriedades.get(ConsumerConfig.GROUP_ID_CONFIG));
        assertEquals("false", propriedades.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
        assertEquals("earliest", propriedades.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
        assertDoesNotThrow(() -> {
            try (var consumidor = new KafkaConsumer<String, byte[]>(propriedades)) {
                consumidor.subscribe(java.util.List.of(ConsumidorLiquidacao.TOPICO));
            }
        });
    }
}
