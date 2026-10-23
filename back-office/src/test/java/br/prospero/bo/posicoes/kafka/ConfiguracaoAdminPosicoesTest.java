package br.prospero.bo.posicoes.kafka;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.api.Test;

class ConfiguracaoAdminPosicoesTest {
    @Test
    void adminAceitaTimeoutsDoConsumo() {
        // A criação valida a configuração sem conectar; ConfigException mataria o consumo antes da prontidão.
        assertDoesNotThrow(() -> { try (Admin admin = Admin.create(ConsumidorPosicoes.configuracaoAdmin("localhost:1"))) { } });
    }
}
