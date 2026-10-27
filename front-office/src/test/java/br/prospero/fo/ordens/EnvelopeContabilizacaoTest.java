package br.prospero.fo.ordens;

import br.prospero.fo.ordens.helper.EnvelopeContabilizacao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EnvelopeContabilizacaoTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NEGOCIO = "ALFA3-2026-10-25-42";
    private static final UUID COMPRA = UUID.randomUUID(), VENDA = UUID.randomUUID();

    private static Map<String, Object> envelope() {
        Map<String, Object> raiz = new LinkedHashMap<>();
        raiz.put("id", UUID.nameUUIDFromBytes(("acompanhamento:" + NEGOCIO).getBytes(StandardCharsets.UTF_8)).toString());
        raiz.put("tipo", "NEGOCIO_CONTABILIZADO");
        raiz.put("versao", 1);
        raiz.put("origem", "back-office");
        raiz.put("causacao", UUID.randomUUID().toString());
        raiz.put("ocorridoEm", "2026-10-25T10:00:00Z");
        raiz.put("traceparent", null);
        raiz.put("dados", new LinkedHashMap<>(Map.of("negocio", NEGOCIO, "ticker", "ALFA3", "quantidade", "10",
                "ordemCompra", COMPRA.toString(), "contaCompra", "CONTA-002",
                "ordemVenda", VENDA.toString(), "contaVenda", "CONTA-003")));
        return raiz;
    }

    private static byte[] bytes(Map<String, Object> envelope) throws Exception { return JSON.writeValueAsBytes(envelope); }

    @Test
    void aceitaSomenteConfirmacaoBoEConservaIdentidadesEQuantidadeExata() throws Exception {
        var raiz = envelope();
        @SuppressWarnings("unchecked") var dados = (Map<String, Object>) raiz.get("dados");
        dados.put("quantidade", Long.toString(Long.MAX_VALUE));
        var evento = EnvelopeContabilizacao.evento(bytes(raiz), "ALFA3");
        assertEquals(Long.MAX_VALUE, evento.quantidade());
        assertEquals(COMPRA, evento.ordemCompra());
        assertEquals(VENDA, evento.ordemVenda());
        assertEquals(NEGOCIO, evento.negocio());
        assertEquals("CONTA-002", evento.contaCompra());
        assertNotNull(evento.assinatura());
        assertNull(evento.traceparent());
    }

    @Test
    void rejeitaEnvelopeExtraIdentidadeOrigemVersaoChaveOuTraceInvalidos() throws Exception {
        for (var mudanca : Map.<String, Object>of("id", UUID.randomUUID().toString(), "origem", "matching-engine",
                "versao", 2, "tipo", "NEGOCIO", "traceparent", "invalido", "extra", "nao-permitido").entrySet()) {
            var raiz = envelope();
            raiz.put(mudanca.getKey(), mudanca.getValue());
            assertThrows(RuntimeException.class, () -> EnvelopeContabilizacao.evento(bytes(raiz), "ALFA3"), mudanca.getKey());
        }
        assertThrows(RuntimeException.class, () -> EnvelopeContabilizacao.evento(bytes(envelope()), "BETA4"));
    }

    @Test
    void rejeitaQuantidadeNumericaNaoPositivaOverflowContaEUuidInvalidos() throws Exception {
        for (Object quantidade : new Object[]{10, "0", "-1", "1.0", "01", "9223372036854775808"}) {
            var raiz = envelope();
            @SuppressWarnings("unchecked") var dados = (Map<String, Object>) raiz.get("dados");
            dados.put("quantidade", quantidade);
            assertThrows(RuntimeException.class, () -> EnvelopeContabilizacao.evento(bytes(raiz), "ALFA3"));
        }
        for (var mudanca : Map.of("contaCompra", "CONTA-999", "ordemVenda", "1-1-1-1-1", "ticker", "FORA3").entrySet()) {
            var raiz = envelope();
            @SuppressWarnings("unchecked") var dados = (Map<String, Object>) raiz.get("dados");
            dados.put(mudanca.getKey(), mudanca.getValue());
            assertThrows(RuntimeException.class, () -> EnvelopeContabilizacao.evento(bytes(raiz), "ALFA3"));
        }
    }

    @Test
    void rejeitaJsonDuplicadoTrailingETamanhoExcessivo() throws Exception {
        String json = JSON.writeValueAsString(envelope());
        assertThrows(RuntimeException.class, () -> EnvelopeContabilizacao.evento(
                (json + " {}").getBytes(StandardCharsets.UTF_8), "ALFA3"));
        assertThrows(RuntimeException.class, () -> EnvelopeContabilizacao.evento(
                json.replace("\"versao\":1", "\"versao\":1,\"versao\":1").getBytes(StandardCharsets.UTF_8), "ALFA3"));
        assertThrows(RuntimeException.class, () -> EnvelopeContabilizacao.evento(new byte[16 * 1024 + 1], "ALFA3"));
    }
}
