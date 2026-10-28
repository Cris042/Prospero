package br.prospero.mo.risco;

import br.prospero.mo.comum.ContratosV5;
import br.prospero.mo.mensageria.dominio.MensagemInvalidaException;
import br.prospero.mo.mensageria.dominio.ParticaoBloqueadaException;
import br.prospero.mo.risco.helper.EnvelopeRisco;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** RF10c2a: o leitor de risco.comandos.v1 reconhece CANCELAMENTO_SOLICITADO e bloqueia, sem descarte. */
class SolicitacaoCancelamentoRiscoTest {
    private static final String TOPICO = "risco.comandos.v1";
    /** Sem JSON estrito legível ou acima do limite, o tipo não é identificável: continua sendo mensagem inválida. */
    private static final Set<String> ILEGIVEIS = Set.of("solicitado-maior-que-16-kib.json", "solicitado-chave-duplicada.json");

    @Test
    void solicitacaoValidaBloqueiaAParticaoComMotivoEstatico() throws Exception {
        var validos = ContratosV5.casos(TOPICO, false);
        assertEquals(1, validos.size());
        var erro = assertThrows(ParticaoBloqueadaException.class, () -> EnvelopeRisco.comando(validos.getFirst().bytes()));
        assertEquals("CANCELAMENTO_SOLICITADO_SEM_COORDENACAO", erro.getMessage());
    }

    @Test
    void corpusNegativoDeTipoReconhecidoTambemBloqueiaSemVazarOPayload() throws Exception {
        var invalidos = ContratosV5.casos(TOPICO, true);
        assertTrue(invalidos.size() >= 15);
        for (var caso : invalidos) {
            if (ILEGIVEIS.contains(caso.nome())) {
                assertThrows(MensagemInvalidaException.class, () -> EnvelopeRisco.comando(caso.bytes()), caso.nome());
                continue;
            }
            var erro = assertThrows(ParticaoBloqueadaException.class, () -> EnvelopeRisco.comando(caso.bytes()), caso.nome());
            // A chave Kafka é conferida pelo consumidor; no envelope a mensagem é válida e continua sem coordenação.
            String esperado = caso.nome().equals("solicitado-conta-diferente-da-chave.json")
                    ? "CANCELAMENTO_SOLICITADO_SEM_COORDENACAO" : "CANCELAMENTO_SOLICITADO_INVALIDO";
            assertEquals(esperado, erro.getMessage(), caso.nome());
        }
    }

    @Test
    void avaliarRiscoContinuaSendoLidoComoAntes() {
        byte[] comando = ("{\"id\":\"8c6b6a0e-3b78-4a52-a3a2-0b3e1d4f9c11\",\"tipo\":\"AVALIAR_RISCO\",\"versao\":1,"
                + "\"origem\":\"front-office\",\"correlacao\":\"7d2d4c0f-8d6b-4f0e-9a64-5a1d2f0f3b21\","
                + "\"ocorridoEm\":\"2026-10-18T12:00:00Z\",\"conta\":\"CONTA-002\",\"traceparent\":null,"
                + "\"dados\":{\"ticker\":\"ALFA3\",\"lado\":\"COMPRA\",\"precoBase\":\"10.00\",\"ordens\":[{\"id\":"
                + "\"4a8f3e0a-6c2b-4a4e-8d3e-0b6a1d2f0c31\",\"tipo\":\"LIMIT\",\"quantidade\":10,\"precoLimite\":\"10.00\","
                + "\"precoGatilho\":null}]}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("CONTA-002", EnvelopeRisco.comando(comando).conta());
    }

    @Test
    void nenhumCaminhoDeProducaoDoMoSerializaOsTiposDeCancelamento() throws Exception {
        try (var fontes = Files.walk(Path.of("src", "main", "java"))) {
            for (Path fonte : fontes.filter(p -> p.toString().endsWith(".java")).toList()) {
                String texto = Files.readString(fonte);
                assertFalse(texto.contains("CANCELAR_ORDEM"), fonte + " emitiria CANCELAR_ORDEM");
                assertFalse(texto.contains("RESULTADO_CANCELAMENTO"), fonte + " emitiria RESULTADO_CANCELAMENTO");
                if (texto.contains("CANCELAMENTO_SOLICITADO")) {
                    assertTrue(fonte.endsWith(Path.of("risco", "helper", "EnvelopeRisco.java")), fonte.toString());
                    assertFalse(texto.contains("put(\"tipo\", \"CANCELAMENTO_SOLICITADO\")"));
                }
                if (texto.contains("CANCELAMENTO_DECIDIDO") || texto.contains("CANCELADA_MANUAL")) {
                    assertTrue(fonte.endsWith(Path.of("matching", "helper", "EnvelopeMatching.java"))
                            || fonte.endsWith(Path.of("matching", "jpa", "RepositorioMatchingJpa.java")), fonte.toString());
                }
            }
        }
    }
}
