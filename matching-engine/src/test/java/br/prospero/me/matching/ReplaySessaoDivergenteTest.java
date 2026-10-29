package br.prospero.me.matching;

import br.prospero.me.matching.helper.EnvelopeMatching;
import br.prospero.me.matching.helper.SessaoDivergenteException;
import br.prospero.me.matching.helper.SessaoMatching;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lacuna 4 da V01: a partição 0 do volume antigo `prospero` guarda sete admissões de 2026-10-24 (checkpoint no
 * offset 6) e duas de 2026-10-25. O replay precisa parar no offset 7 com diagnóstico explícito, nunca com uma
 * IllegalArgumentException genérica.
 */
class ReplaySessaoDivergenteTest {
    private static final LocalDate SESSAO_LOG = LocalDate.of(2026, 10, 24);
    private static final LocalDate SESSAO_NOVA = LocalDate.of(2026, 10, 25);

    @Test
    void replayDoLogAntigoParaNoPrimeiroComandoDeOutraSessaoComDiagnostico() throws IOException {
        List<String> log = comandos();
        assertEquals(9, log.size());
        var motor = new SessaoMatching();
        LocalDate persistida = SESSAO_LOG;
        for (int offset = 0; offset <= 6; offset++) {
            var comando = EnvelopeMatching.comando(log.get(offset).getBytes(StandardCharsets.UTF_8), "ALFA3");
            persistida = SessaoMatching.sessaoDoCheckpoint(comando, persistida);
            motor.processar(comando);
        }
        assertEquals(SESSAO_LOG, motor.sessao());

        var divergente = EnvelopeMatching.comando(log.get(7).getBytes(StandardCharsets.UTF_8), "ALFA3");
        LocalDate checkpoint = persistida;
        var erro = assertThrows(SessaoDivergenteException.class,
                () -> SessaoMatching.sessaoDoCheckpoint(divergente, checkpoint));
        assertEquals(SESSAO_LOG, erro.sessaoLog());
        assertEquals(SESSAO_NOVA, erro.sessaoComando());
        String diagnostico = erro.diagnostico(0, 7);
        assertTrue(diagnostico.contains("partição 0, offset 7"), diagnostico);
        assertTrue(diagnostico.contains("sessão 2026-10-25") && diagnostico.contains("sessão 2026-10-24"), diagnostico);
        assertTrue(diagnostico.endsWith(SessaoDivergenteException.PROCEDIMENTO), diagnostico);
        assertFalse(diagnostico.contains("CONTA-") || diagnostico.contains(divergente.admissao().ordem().toString()),
                "diagnóstico não expõe conta nem ordem");

        var noMotor = assertThrows(SessaoDivergenteException.class, () -> motor.processar(divergente));
        assertEquals(SESSAO_NOVA, noMotor.sessaoComando());
        assertEquals(SESSAO_LOG, motor.sessao(), "nenhuma mutação após a recusa");
    }

    private static List<String> comandos() throws IOException {
        try (InputStream entrada = ReplaySessaoDivergenteTest.class.getResourceAsStream("/replay-v01/matching.comandos.v1-0.ndjson")) {
            assertNotNull(entrada);
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
        }
    }
}
