package br.prospero.fo.comum.actor;

import br.prospero.fo.comum.dominio.RegraNegocioException;
import br.prospero.fo.comum.QuarentenaMemoria;
import br.prospero.fo.comum.dominio.MensagemQuarentena;
import br.prospero.fo.comum.dominio.Reaplicacao;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-019: reprocessamento idempotente, falha mantém a mensagem pendente e descarte é decisão explícita. */
class ReprocessarQuarentenaActorTest {
    private static final String TOPICO = "risco.resultados.v1";
    private final QuarentenaMemoria quarentena = new QuarentenaMemoria();
    private final List<Long> reaplicadas = new ArrayList<>();
    private boolean falhar;
    private final Reaplicacao reaplicacao = new Reaplicacao() {
        @Override public boolean suporta(String topico) { return topico.equals(TOPICO); }
        @Override public void reaplicar(MensagemQuarentena m) {
            if (falhar) throw new IllegalStateException("ainda inválida");
            reaplicadas.add(m.deslocamento());
        }
    };
    private final ReprocessarQuarentenaActor actor = new ReprocessarQuarentenaActor(quarentena, reaplicacao, new SimpleMeterRegistry());

    private long quarentenar(String topico, long offset, byte[] payload) {
        quarentena.registrar(new MensagemQuarentena(topico, 0, offset, "CHAVE", null, payload, "contrato", 1));
        return quarentena.pendentes(100).getLast().id();
    }

    @Test
    void reprocessaUmaVezERepeticaoEInocua() {
        long id = quarentenar(TOPICO, 7, "{}".getBytes(StandardCharsets.UTF_8));
        var primeira = actor.reprocessar(id);
        assertEquals("REPROCESSADA", primeira.resolucao());
        assertFalse(primeira.repetido());
        var segunda = actor.reprocessar(id);
        assertTrue(segunda.repetido());
        assertEquals(List.of(7L), reaplicadas, "a segunda chamada não reaplica");
        assertTrue(quarentena.pendentes(10).isEmpty());
        assertTrue(actor.descartar(id).repetido(), "descartar depois de reprocessar não muda o desfecho");
    }

    @Test
    void falhaDeNovoMantemPendenteContaTentativaEResponde409() {
        long id = quarentenar(TOPICO, 3, "{}".getBytes(StandardCharsets.UTF_8));
        falhar = true;
        var erro = assertThrows(RegraNegocioException.class, () -> actor.reprocessar(id));
        assertEquals("REPROCESSAMENTO_FALHOU", erro.codigo());
        var item = quarentena.buscar(id).orElseThrow();
        assertTrue(item.pendente());
        assertEquals(2, item.mensagem().tentativas());
        assertEquals("reprocessamento: IllegalStateException", item.mensagem().motivo());
        falhar = false;
        assertEquals("REPROCESSADA", actor.reprocessar(id).resolucao());
    }

    @Test
    void recusaLinhaSemPayloadTopicoSemReprocessadorEIdDesconhecido() {
        long legado = quarentenar(TOPICO, 1, null);
        assertEquals("QUARENTENA_SEM_PAYLOAD", assertThrows(RegraNegocioException.class, () -> actor.reprocessar(legado)).codigo());
        long alheio = quarentenar("outro.topico.v1", 1, new byte[]{1});
        assertEquals("TOPICO_SEM_REPROCESSADOR", assertThrows(RegraNegocioException.class, () -> actor.reprocessar(alheio)).codigo());
        assertEquals("QUARENTENA_DESCONHECIDA", assertThrows(RegraNegocioException.class, () -> actor.reprocessar(999)).codigo());
        assertEquals("DESCARTADA", actor.descartar(legado).resolucao());
        assertTrue(actor.descartar(legado).repetido());
        assertTrue(reaplicadas.isEmpty());
        assertEquals("LIMITE_INVALIDO", assertThrows(RegraNegocioException.class, () -> actor.pendentes(0)).codigo());
    }
}
