package br.prospero.bo.razao;

import br.prospero.bo.posicoes.dominio.EventoMatching;
import br.prospero.bo.posicoes.dominio.ProjecaoIncoerenteException;
import br.prospero.bo.posicoes.helper.EnvelopeMatching;
import br.prospero.bo.razao.dominio.Calendario;
import br.prospero.bo.razao.dominio.FatoNegocio;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Datas fixas do ADR-016; a lista replica a migração V3 (conferida também no IT PostgreSQL). */
class CalendarioTest {
    static final Set<LocalDate> FERIADOS = Set.copyOf(Stream.of(
            "2026-01-01", "2026-04-21", "2026-05-01", "2026-09-07", "2026-10-12", "2026-11-02", "2026-11-15",
            "2026-11-20", "2026-12-25", "2027-01-01", "2027-04-21", "2027-05-01", "2027-09-07", "2027-10-12",
            "2027-11-02", "2027-11-15", "2027-11-20", "2027-12-25").map(LocalDate::parse).toList());
    static final Calendario CALENDARIO = new Calendario(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), FERIADOS);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void contaDoisDiasUteisNaSemanaSemFeriado() {
        assertEquals(dia("2026-10-21"), CALENDARIO.liquidacao(dia("2026-10-19")), "segunda → quarta");
        assertEquals(dia("2026-10-26"), CALENDARIO.liquidacao(dia("2026-10-22")), "quinta → segunda");
        assertEquals(dia("2026-10-27"), CALENDARIO.liquidacao(dia("2026-10-23")), "sexta → terça");
    }

    @Test
    void feriadoNoIntervaloOuNoProprioD2EmpurraParaOProximoDiaUtil() {
        assertEquals(dia("2026-10-14"), CALENDARIO.liquidacao(dia("2026-10-09")), "segunda 12/10 é feriado");
        assertEquals(dia("2026-11-23"), CALENDARIO.liquidacao(dia("2026-11-18")), "D+2 cairia em 20/11");
        assertEquals(dia("2026-12-28"), CALENDARIO.liquidacao(dia("2026-12-23")), "24/12 é útil; 25/12 não");
        assertEquals(dia("2027-01-04"), CALENDARIO.liquidacao(dia("2026-12-30")), "virada do ano com 01/01");
    }

    @Test
    void negocioEmDiaNaoUtilContaAPartirDoProximoDiaUtil() {
        assertEquals(dia("2026-10-28"), CALENDARIO.liquidacao(dia("2026-10-24")), "sábado → base segunda");
        assertEquals(dia("2026-10-28"), CALENDARIO.liquidacao(dia("2026-10-25")), "domingo → base segunda");
        assertEquals(dia("2026-10-15"), CALENDARIO.liquidacao(dia("2026-10-12")), "feriado → base terça");
        assertFalse(CALENDARIO.util(dia("2026-10-24")));
        assertFalse(CALENDARIO.util(dia("2026-10-12")));
        assertTrue(CALENDARIO.util(dia("2026-12-24")));
    }

    @Test
    void pertoDaMeiaNoiteUsaDataSessaoDoEnvelopeENuncaOcorridoEmUtc() throws Exception {
        // 02:59:59Z de sábado ainda é 23:59:59 de sexta em São Paulo; 03:00Z de sexta já é meia-noite local.
        for (String ocorrido : new String[]{"2026-10-24T02:59:59Z", "2026-10-23T03:00:00Z", "2026-10-24T23:59:59Z"}) {
            EventoMatching evento = envelope(ocorrido, "2026-10-23");
            FatoNegocio fato = FatoNegocio.de(evento, (EventoMatching.Negocio) evento.detalhe(), CALENDARIO);
            assertEquals(dia("2026-10-23"), fato.sessao(), ocorrido);
            assertEquals(dia("2026-10-27"), fato.liquidacao(), ocorrido);
        }
    }

    @Test
    void foraDaTabelaFalhaFechadoSemSuporDiaUtil() {
        assertEquals(dia("2027-12-31"), CALENDARIO.liquidacao(dia("2027-12-29")));
        for (String sessao : new String[]{"2027-12-30", "2027-12-31", "2028-01-03", "2025-12-31", "2025-12-26"}) {
            var erro = assertThrows(ProjecaoIncoerenteException.class, () -> CALENDARIO.liquidacao(dia(sessao)), sessao);
            assertEquals("CALENDARIO_INSUFICIENTE", erro.getMessage());
        }
    }

    @Test
    void tabelaIncoerenteNaoFormaCalendario() {
        assertThrows(IllegalArgumentException.class, () -> new Calendario(dia("2027-01-01"), dia("2026-01-01"), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Calendario(dia("2026-01-01"), dia("2026-12-31"), Set.of(dia("2027-01-01"))));
        assertThrows(IllegalArgumentException.class, () -> new Calendario(null, dia("2026-12-31"), Set.of()));
        assertThrows(NullPointerException.class, () -> CALENDARIO.liquidacao(null));
    }

    private static LocalDate dia(String texto) { return LocalDate.parse(texto); }

    private static EventoMatching envelope(String ocorridoEm, String sessao) throws Exception {
        UUID intencao = UUID.randomUUID();
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("negocio", "ALFA3-" + sessao + "-1"); dados.put("preco", "20.00"); dados.put("quantidade", 100);
        dados.put("ordemCompra", UUID.randomUUID().toString()); dados.put("intencaoCompra", intencao.toString());
        dados.put("contaCompra", "CONTA-001"); dados.put("ordemVenda", UUID.randomUUID().toString());
        dados.put("intencaoVenda", UUID.randomUUID().toString()); dados.put("contaVenda", "CONTA-002");
        dados.put("agressor", "COMPRA");
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", UUID.randomUUID().toString()); e.put("tipo", "NEGOCIO"); e.put("versao", 1);
        e.put("origem", "matching-engine");
        e.put("correlacao", UUID.nameUUIDFromBytes(("admissao:" + intencao).getBytes(StandardCharsets.UTF_8)).toString());
        e.put("ocorridoEm", ocorridoEm); e.put("ticker", "ALFA3"); e.put("dataSessao", sessao);
        e.put("traceparent", null); e.put("dados", dados);
        return EnvelopeMatching.evento(JSON.writeValueAsBytes(e), "ALFA3");
    }
}
