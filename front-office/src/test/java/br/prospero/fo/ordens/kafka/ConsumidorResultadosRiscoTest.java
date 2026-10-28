package br.prospero.fo.ordens.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.prospero.fo.comum.ContratosV5;
import br.prospero.fo.comum.dominio.ParticaoBloqueadaException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/** ADR-013 RF10c2: resultado de cancelamento reconhecido e inválido bloqueia a partição em vez de ser descartado. */
class ConsumidorResultadosRiscoTest {
    @Test
    void contaDoResultadoDeCancelamentoDiferenteDaChaveBloqueiaAParticao() throws Exception {
        var caso = ContratosV5.casos("risco.resultados.v1", true).stream()
                .filter(c -> c.nome().equals("resultado-conta-diferente-da-chave.json")).findFirst().orElseThrow();
        var telemetria = OpenTelemetry.noop();
        var consumidor = new ConsumidorResultadosRisco(null, null, null, telemetria, telemetria.getTracer("teste"),
                new SimpleMeterRegistry(), false);
        var registro = new ConsumerRecord<>("risco.resultados.v1", 0, 0L, caso.chave(), caso.bytes());
        var bloqueio = assertThrows(ParticaoBloqueadaException.class, () -> consumidor.processar(registro));
        assertEquals("RESULTADO_CANCELAMENTO_INVALIDO", bloqueio.getMessage());
    }
}
