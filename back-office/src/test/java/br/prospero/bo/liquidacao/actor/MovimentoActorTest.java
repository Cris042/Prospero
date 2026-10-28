package br.prospero.bo.liquidacao.actor;

import br.prospero.bo.liquidacao.dominio.MovimentoInvalidoException;
import br.prospero.bo.posicoes.dominio.SituacaoPosicoes;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class MovimentoActorTest {
    @Test
    void avancoDeDataExigeLagZeroMesmoComLeituraEmDia() {
        var actor = new MovimentoActor();
        actor.situacao = new SituacaoPosicoes() {
            @Override public boolean emDia() { return true; }
            @Override public boolean semLag() { return false; }
        };
        var erro = assertThrows(MovimentoInvalidoException.class, () -> actor.avancar(LocalDate.of(2026, 10, 26), "s", "j"));
        assertEquals("LIQUIDACAO_INDISPONIVEL", erro.getMessage());
    }
}
