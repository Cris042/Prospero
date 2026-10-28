package br.prospero.mo.risco.kafka;

import br.prospero.mo.cancelamento.actor.SolicitarCancelamentoActor;
import br.prospero.mo.cancelamento.dominio.SolicitacaoCancelamento;
import br.prospero.mo.comum.ContratosV5;
import br.prospero.mo.mensageria.dominio.ParticaoBloqueadaException;
import br.prospero.mo.risco.actor.AvaliarRiscoActor;
import br.prospero.mo.risco.dominio.ComandoRisco;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** RF10c2b: o consumidor de risco roteia por tipo e confere a chave de partição também para o cancelamento. */
class ConsumidorComandosRiscoTest {
    private final List<SolicitacaoCancelamento> solicitadas = new ArrayList<>();
    private final List<ComandoRisco> avaliadas = new ArrayList<>();
    private final String[] traceparent = new String[1];

    private ConsumidorComandosRisco consumidor() {
        var avaliar = new AvaliarRiscoActor(null, null, null, null) {
            @Override
            public Avaliacao avaliar(ComandoRisco comando, String trace) {
                avaliadas.add(comando);
                return null;
            }
        };
        var solicitar = new SolicitarCancelamentoActor(null, null, null, null) {
            @Override
            public br.prospero.mo.cancelamento.dominio.OperacaoCancelamento solicitar(SolicitacaoCancelamento s, String trace) {
                solicitadas.add(s);
                traceparent[0] = trace;
                return null;
            }
        };
        var telemetria = OpenTelemetry.noop();
        return new ConsumidorComandosRisco(avaliar, solicitar, (t, p, d, m) -> { }, null, telemetria,
                new SimpleMeterRegistry(), false);
    }

    private static ConsumerRecord<String, byte[]> registro(String chave, byte[] bytes) {
        return new ConsumerRecord<>("risco.comandos.v1", 0, 0L, chave, bytes);
    }

    @Test
    void solicitacaoVaiParaOActorDeCancelamentoESoParaEle() throws Exception {
        consumidor().processar(registro("CONTA-002", ContratosV5.ler("risco.comandos.v1/CONTA-002/cancelamento-solicitado.json")));
        assertEquals(1, solicitadas.size());
        assertEquals("a0000000-0000-4000-8000-000000000010", solicitadas.getFirst().ordem().toString());
        assertNull(traceparent[0], "sem telemetria ativa o traceparent é nulo");
        assertTrue(avaliadas.isEmpty());
    }

    @Test
    void avaliarRiscoContinuaIndoParaOActorDeRisco() {
        byte[] comando = ("{\"id\":\"8c6b6a0e-3b78-4a52-a3a2-0b3e1d4f9c11\",\"tipo\":\"AVALIAR_RISCO\",\"versao\":1,"
                + "\"origem\":\"front-office\",\"correlacao\":\"7d2d4c0f-8d6b-4f0e-9a64-5a1d2f0f3b21\","
                + "\"ocorridoEm\":\"2026-10-18T12:00:00Z\",\"conta\":\"CONTA-002\",\"traceparent\":null,"
                + "\"dados\":{\"ticker\":\"ALFA3\",\"lado\":\"COMPRA\",\"precoBase\":\"10.00\",\"ordens\":[{\"id\":"
                + "\"4a8f3e0a-6c2b-4a4e-8d3e-0b6a1d2f0c31\",\"tipo\":\"LIMIT\",\"quantidade\":10,\"precoLimite\":\"10.00\","
                + "\"precoGatilho\":null}]}}").getBytes(StandardCharsets.UTF_8);
        consumidor().processar(registro("CONTA-002", comando));
        assertEquals(1, avaliadas.size());
        assertTrue(solicitadas.isEmpty());
    }

    @Test
    void chaveDiferenteDaContaBloqueiaOCancelamentoSemEntregarAoActor() throws Exception {
        var erro = assertThrows(ParticaoBloqueadaException.class, () -> consumidor()
                .processar(registro("CONTA-001", ContratosV5.ler("risco.comandos.v1/CONTA-002/cancelamento-solicitado.json"))));
        assertEquals("CANCELAMENTO_CHAVE_DIVERGENTE", erro.getMessage());
        assertTrue(solicitadas.isEmpty());
    }

    @Test
    void solicitacaoInvalidaContinuaBloqueandoSemEntregarAoActor() throws Exception {
        for (var caso : ContratosV5.casos("risco.comandos.v1", true)) {
            if (caso.nome().equals("solicitado-conta-diferente-da-chave.json")) continue;
            try {
                consumidor().processar(registro(caso.chave(), caso.bytes()));
                fail(caso.nome());
            } catch (ParticaoBloqueadaException | br.prospero.mo.mensageria.dominio.MensagemInvalidaException esperado) {
                // bloqueio (tipo reconhecido) ou ilegível (invalido): nunca chega ao actor
            }
        }
        assertTrue(solicitadas.isEmpty());
    }
}
