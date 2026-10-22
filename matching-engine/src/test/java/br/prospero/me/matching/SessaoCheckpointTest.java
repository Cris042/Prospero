package br.prospero.me.matching;

import br.prospero.me.matching.helper.EnvelopeMatching;
import br.prospero.me.matching.helper.SessaoMatching;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** RF05c.4b: encerramento com data divergente nunca para o escritor nem troca a sessão do checkpoint. */
class SessaoCheckpointTest {
    private static final LocalDate DIA = LocalDate.of(2026, 10, 18);

    @Test
    void encerramentoPreservaSessaoPersistidaEAdmissaoDivergenteContinuaParando() throws Exception {
        var outroDia = EnvelopeMatching.comando(EncerramentoIntegracaoTest.encerrar("ALFA3", "2026-10-19"), "ALFA3");
        assertEquals(DIA, SessaoMatching.sessaoDoCheckpoint(outroDia, DIA));
        assertEquals(LocalDate.of(2026, 10, 19), SessaoMatching.sessaoDoCheckpoint(outroDia, null));
        var admissao = EnvelopeMatching.comando(StopsIntegracaoTest.comando("x", "LIMIT", "COMPRA", 1, "9.00", null), "ALFA3");
        assertEquals(DIA, SessaoMatching.sessaoDoCheckpoint(admissao, DIA));
        assertThrows(IllegalArgumentException.class, () -> SessaoMatching.sessaoDoCheckpoint(admissao, DIA.plusDays(1)));
    }
}
