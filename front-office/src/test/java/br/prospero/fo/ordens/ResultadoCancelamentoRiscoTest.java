package br.prospero.fo.ordens;

import br.prospero.fo.comum.ContratosV5;
import br.prospero.fo.comum.dominio.MensagemInvalidaException;
import br.prospero.fo.comum.dominio.ParticaoBloqueadaException;
import br.prospero.fo.ordens.helper.EnvelopeRisco;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** RF10c2a: o leitor de risco.resultados.v1 reconhece RESULTADO_CANCELAMENTO e bloqueia, sem descarte. */
class ResultadoCancelamentoRiscoTest {
    private static final String TOPICO = "risco.resultados.v1";
    /** Sem JSON estrito legível ou acima do limite, o tipo não é identificável: continua sendo mensagem inválida. */
    private static final Set<String> ILEGIVEIS = Set.of("resultado-maior-que-16-kib.json", "resultado-chave-duplicada.json");

    @Test
    void resultadosValidosBloqueiamAParticaoComMotivoEstatico() throws Exception {
        var validos = ContratosV5.casos(TOPICO, false);
        assertEquals(2, validos.size());
        for (var caso : validos) {
            var erro = assertThrows(ParticaoBloqueadaException.class, () -> EnvelopeRisco.resultado(caso.bytes()), caso.nome());
            assertEquals("RESULTADO_CANCELAMENTO_SEM_LEITOR", erro.getMessage());
        }
    }

    @Test
    void corpusNegativoDeTipoReconhecidoTambemBloqueiaSemVazarOPayload() throws Exception {
        var invalidos = ContratosV5.casos(TOPICO, true);
        assertTrue(invalidos.size() >= 15);
        for (var caso : invalidos) {
            if (ILEGIVEIS.contains(caso.nome())) {
                assertThrows(MensagemInvalidaException.class, () -> EnvelopeRisco.resultado(caso.bytes()), caso.nome());
                continue;
            }
            var erro = assertThrows(ParticaoBloqueadaException.class, () -> EnvelopeRisco.resultado(caso.bytes()), caso.nome());
            // A chave Kafka é conferida pelo consumidor; no envelope a mensagem é válida e continua sem leitor.
            String esperado = caso.nome().equals("resultado-conta-diferente-da-chave.json")
                    ? "RESULTADO_CANCELAMENTO_SEM_LEITOR" : "RESULTADO_CANCELAMENTO_INVALIDO";
            assertEquals(esperado, erro.getMessage(), caso.nome());
        }
    }

    @Test
    void resultadoDeRiscoComumContinuaSendoLidoComoAntes() {
        byte[] resultado = ("{\"id\":\"8c6b6a0e-3b78-4a52-a3a2-0b3e1d4f9c11\",\"tipo\":\"RESULTADO_RISCO\",\"versao\":1,"
                + "\"origem\":\"middle-office\",\"correlacao\":\"7d2d4c0f-8d6b-4f0e-9a64-5a1d2f0f3b21\","
                + "\"ocorridoEm\":\"2026-10-18T12:00:00Z\",\"conta\":\"CONTA-002\",\"traceparent\":null,"
                + "\"dados\":{\"resultado\":\"REJEITADA\",\"motivo\":\"SALDO_INSUFICIENTE\",\"valorReservado\":null,"
                + "\"quantidadeReservada\":null}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            EnvelopeRisco.resultado(resultado);
        } catch (ParticaoBloqueadaException erro) {
            fail("RESULTADO_RISCO nunca bloqueia");
        } catch (MensagemInvalidaException esperado) {
            // motivo fora do enum do domínio é aceitável aqui; o ponto é não bloquear.
        }
    }

    @Test
    void nenhumCaminhoDeProducaoDoFoSerializaOsTiposDeCancelamento() throws Exception {
        try (Stream<Path> fontes = Files.walk(Path.of("src", "main", "java"))) {
            for (Path fonte : fontes.filter(p -> p.toString().endsWith(".java")).toList()) {
                String texto = Files.readString(fonte);
                assertFalse(texto.contains("CANCELAMENTO_SOLICITADO"), fonte + " emitiria CANCELAMENTO_SOLICITADO");
                assertFalse(texto.contains("CANCELAR_ORDEM"), fonte + " emitiria CANCELAR_ORDEM");
                if (texto.contains("RESULTADO_CANCELAMENTO")) {
                    assertTrue(fonte.endsWith(Path.of("ordens", "helper", "EnvelopeRisco.java")), fonte.toString());
                    assertFalse(texto.contains("put(\"tipo\", \"RESULTADO_CANCELAMENTO\")"));
                }
            }
        }
    }
}
