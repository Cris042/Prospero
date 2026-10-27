package br.prospero.fo.ordens;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.dominio.RegraNegocioException;
import br.prospero.fo.ordens.actor.ConsultarTrajetoriaActor;
import br.prospero.fo.ordens.dominio.MarcoTrajetoria;
import br.prospero.fo.ordens.dominio.PaginaTrajetoria;
import br.prospero.fo.ordens.helper.CursorTrajetoria;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TrajetoriaConsultaTest {
    private static final String CONTA = "CONTA-002";

    @Test
    void cursorVinculaContaOrdemEPosicaoSemTruncarLong() {
        UUID ordem = UUID.randomUUID();
        assertEquals(0, CursorTrajetoria.decodificar(null, CONTA, ordem));
        String cursor = CursorTrajetoria.codificar(CONTA, ordem, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, CursorTrajetoria.decodificar(cursor, CONTA, ordem));
        assertThrows(RegraNegocioException.class, () -> CursorTrajetoria.decodificar(cursor, "CONTA-003", ordem));
        assertThrows(RegraNegocioException.class, () -> CursorTrajetoria.decodificar(cursor, CONTA, UUID.randomUUID()));
    }

    @Test
    void cursorRecusaEntradaMalformadaPosicaoInvalidaEOverflow() {
        UUID ordem = UUID.randomUUID();
        for (String cursor : new String[]{"", "!", "a".repeat(161),
                codificar(CONTA + ":" + ordem + ":0"), codificar(CONTA + ":" + ordem + ":-1"),
                codificar(CONTA + ":" + ordem + ":9223372036854775808"),
                codificar(CONTA + ":" + ordem + ":01"), codificar(CONTA + ":" + ordem + ":1:extra")}) {
            RegraNegocioException erro = assertThrows(RegraNegocioException.class,
                    () -> CursorTrajetoria.decodificar(cursor, CONTA, ordem));
            assertEquals("CURSOR_INVALIDO", erro.codigo());
            assertEquals(Categoria.ENTRADA_INVALIDA, erro.categoria());
        }
    }

    @Test
    void paginaCortaExcedenteEAvancaDoUltimoEventoEntregue() {
        UUID ordem = UUID.randomUUID();
        Instant inicio = Instant.parse("2026-10-25T10:00:00Z");
        var marcos = List.of(marco(3, inicio), marco(7, inicio), marco(12, inicio));
        var actor = new ConsultarTrajetoriaActor(new RepositorioOrdensMemoria(), (conta, id, apos, limite) -> {
            assertEquals(CONTA, conta);
            assertEquals(ordem, id);
            assertEquals(1, apos);
            assertEquals(3, limite);
            return Optional.of(new PaginaTrajetoria(Long.MAX_VALUE, true, inicio, marcos));
        });
        var pagina = actor.consultar(CONTA, ordem, CursorTrajetoria.codificar(CONTA, ordem, 1), 2);
        assertEquals(List.of("3", "7"), pagina.eventos().stream().map(e -> e.sequencia()).toList());
        assertEquals(marcos.getFirst().evento(), pagina.eventos().getFirst().evento());
        assertEquals(7, CursorTrajetoria.decodificar(pagina.proximoCursor(), CONTA, ordem));
        assertEquals(Long.toString(Long.MAX_VALUE), pagina.revisao());
        assertEquals(inicio, pagina.disponivelDesde());
        assertTrue(pagina.historicoCompleto());
    }

    @Test
    void legadoVazioPreservaIncompletudeSemFabricarMarcosOuCursor() {
        UUID ordem = UUID.randomUUID();
        var actor = new ConsultarTrajetoriaActor(new RepositorioOrdensMemoria(), (conta, id, apos, limite) -> {
            assertEquals(21, limite);
            assertEquals(0, apos);
            return Optional.of(new PaginaTrajetoria(4, false, null, List.of()));
        });
        var pagina = actor.consultar(CONTA, ordem, null, null);
        assertFalse(pagina.historicoCompleto());
        assertNull(pagina.disponivelDesde());
        assertNull(pagina.proximoCursor());
        assertTrue(pagina.eventos().isEmpty());
    }

    @Test
    void paginaExataNaoOfereceContinuacaoEOrdemAusenteTem404() {
        UUID ordem = UUID.randomUUID();
        var actor = new ConsultarTrajetoriaActor(new RepositorioOrdensMemoria(), (conta, id, apos, limite) ->
                Optional.of(new PaginaTrajetoria(1, false, null, List.of(marco(1, Instant.now())))));
        assertNull(actor.consultar(CONTA, ordem, null, 1).proximoCursor());
        var ausente = new ConsultarTrajetoriaActor(new RepositorioOrdensMemoria(), (conta, id, apos, limite) -> Optional.empty());
        var erro = assertThrows(RegraNegocioException.class, () -> ausente.consultar(CONTA, ordem, null, 100));
        assertEquals(Categoria.NAO_ENCONTRADO, erro.categoria());
        assertEquals("ORDEM_NAO_ENCONTRADA", erro.codigo());
    }

    private static MarcoTrajetoria marco(long sequencia, Instant instante) {
        return new MarcoTrajetoria(sequencia, "front-office", "ORDEM_RECEBIDA", UUID.randomUUID(), null,
                null, null, instante, "10", null, null);
    }

    private static String codificar(String texto) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }
}
