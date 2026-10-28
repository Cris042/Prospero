package br.prospero.mo.matching;

import br.prospero.mo.comum.ContratosV5;
import br.prospero.mo.matching.helper.EnvelopeMatching;
import br.prospero.mo.mensageria.dominio.MensagemInvalidaException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** RF10c2a: o leitor de matching do MO valida estritamente o envelope v5 contra as fixtures compartilhadas. */
class CancelamentoManualLeitorTest {
    private static final String TOPICO = "matching.eventos.v1";

    @Test
    void fixturesValidasSaoAceitasNaV5() throws Exception {
        var validos = ContratosV5.casos(TOPICO, false);
        assertEquals(5, validos.size());
        for (var caso : validos) {
            JsonNode e = EnvelopeMatching.ler(caso.bytes());
            assertEquals(5, e.get("versao").intValue(), caso.nome());
            assertEquals("ALFA3", EnvelopeMatching.texto(e, "ticker"));
        }
    }

    @Test
    void corpusNegativoFalhaFechadoSemVazarOPayload() throws Exception {
        var invalidos = ContratosV5.casos(TOPICO, true);
        assertTrue(invalidos.size() >= 40, "corpus negativo de eventos v5");
        for (var caso : invalidos) {
            // O consumidor confere a chave Kafka logo após ler (ConsumidorMatching).
            var erro = assertThrows(MensagemInvalidaException.class, () -> {
                JsonNode lido = EnvelopeMatching.ler(caso.bytes());
                EnvelopeMatching.exigir(EnvelopeMatching.texto(lido, "ticker").equals(caso.chave()), "chave");
            }, caso.nome());
            assertTrue(erro.getMessage().startsWith("matching: "), caso.nome());
            assertFalse(erro.getMessage().contains("forjado") || erro.getMessage().contains("\n"), caso.nome());
        }
    }

    @Test
    void replayMistoDeEnvelopesHistoricosV1aV4ComV5() throws Exception {
        int historicos = 0;
        for (int v = 1; v <= 4; v++) {
            var arquivo = Path.of("..", "matching-engine", "src", "test", "resources", "rf10a", "historico-v" + v + "-envelopes.ndjson");
            for (String linha : Files.readAllLines(arquivo, StandardCharsets.UTF_8)) {
                JsonNode e = EnvelopeMatching.ler(linha.substring(linha.indexOf('\t') + 1).getBytes(StandardCharsets.UTF_8));
                assertTrue(e.get("versao").intValue() <= v, "v" + v);
                historicos++;
            }
        }
        assertTrue(historicos > 50);
        assertEquals(List.of(5), ContratosV5.casos(TOPICO, false).stream()
                .map(c -> EnvelopeMatching.ler(c.bytes()).get("versao").intValue()).distinct().toList());
    }
}
