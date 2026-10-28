package br.prospero.bo.posicoes.kafka;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProntidaoLagPosicoesTest {
    @Test
    void gaugeDeLagExisteEIndicaNaoValidadoAntesDoPrimeiroCiclo() {
        var metricas = new SimpleMeterRegistry();
        var consumidor = new ConsumidorPosicoes(null, null, metricas, "localhost:9092", false, 50);
        var gauge = metricas.find("prospero.posicoes.lag_registros").gauge();
        assertNotNull(gauge, "o gauge de lag deve existir ao lado de em_dia");
        assertEquals(-1.0, gauge.value());
        assertEquals(0.0, metricas.find("prospero.posicoes.em_dia").gauge().value());
        assertFalse(consumidor.emDia());
        assertFalse(consumidor.semLag());
    }

    @Test
    void prontidaoSegueSituacaoDeLeitura() {
        var consumidor = new ConsumidorPosicoes(null, null, new SimpleMeterRegistry(), "localhost:9092", false, 50);
        var resposta = new ProntidaoPosicoes(consumidor).call();
        assertEquals(HealthCheckResponse.Status.DOWN, resposta.getStatus());
    }
}
