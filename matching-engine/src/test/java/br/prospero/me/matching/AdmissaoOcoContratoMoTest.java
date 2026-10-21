package br.prospero.me.matching;

import br.prospero.me.livro.dominio.Lado;
import br.prospero.me.livro.dominio.TipoOrdem;
import br.prospero.me.livro.dominio.Validade;
import br.prospero.me.matching.helper.EnvelopeMatching;
import br.prospero.me.matching.helper.SessaoMatching;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static br.prospero.me.matching.StopsIntegracaoTest.comando;
import static br.prospero.me.matching.StopsIntegracaoTest.processar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RF05c.3b2 (QA): bytes v3 exatamente como o MO os serializa (fixados em EnvelopeAdmissaoOcoTest do MO) são aceitos
 * pelo parser estrito do ME e produzem o par esperado. Alterar um lado exige alterar o outro.
 */
class AdmissaoOcoContratoMoTest {
    static final String TRACE = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    static final String COMPRA_STOP = "{\"id\":\"cdc25fd2-7b6d-32cb-83c0-b11821e36367\",\"tipo\":\"ORDEM_ADMITIDA\",\"versao\":3,"
            + "\"origem\":\"middle-office\",\"correlacao\":\"00000000-0000-0000-0000-0000000000a1\","
            + "\"ocorridoEm\":\"2026-10-18T12:00:00Z\",\"conta\":\"CONTA-002\",\"traceparent\":\"" + TRACE + "\","
            + "\"dados\":{\"ticker\":\"ALFA3\",\"lado\":\"COMPRA\",\"dataSessao\":\"2026-10-18\",\"precoMaximo\":\"11.22\","
            + "\"pernas\":[{\"papel\":\"TAKE_PROFIT\",\"ordem\":\"00000000-0000-0000-0000-0000000000b1\",\"tipo\":\"LIMIT\","
            + "\"validade\":\"DIA\",\"quantidade\":2,\"precoLimite\":\"9.80\"},{\"papel\":\"STOP_LOSS\","
            + "\"ordem\":\"00000000-0000-0000-0000-0000000000c1\",\"tipo\":\"STOP\",\"validade\":\"DIA\",\"quantidade\":2,"
            + "\"precoLimite\":null,\"precoGatilho\":\"10.20\"}]}}";

    static final String COMPRA_STOP_LIMIT = "{\"id\":\"3d660176-afef-3173-81c2-085a5b5aea42\",\"tipo\":\"ORDEM_ADMITIDA\","
            + "\"versao\":3,\"origem\":\"middle-office\",\"correlacao\":\"00000000-0000-0000-0000-0000000000a2\","
            + "\"ocorridoEm\":\"2026-10-18T12:00:00Z\",\"conta\":\"CONTA-002\",\"traceparent\":null,"
            + "\"dados\":{\"ticker\":\"ALFA3\",\"lado\":\"COMPRA\",\"dataSessao\":\"2026-10-18\",\"precoMaximo\":\"10.30\","
            + "\"pernas\":[{\"papel\":\"TAKE_PROFIT\",\"ordem\":\"00000000-0000-0000-0000-0000000000b2\",\"tipo\":\"LIMIT\","
            + "\"validade\":\"DIA\",\"quantidade\":2,\"precoLimite\":\"9.80\"},{\"papel\":\"STOP_LOSS\","
            + "\"ordem\":\"00000000-0000-0000-0000-0000000000c2\",\"tipo\":\"STOP_LIMIT\",\"validade\":\"DIA\",\"quantidade\":2,"
            + "\"precoLimite\":\"10.30\",\"precoGatilho\":\"10.20\"}]}}";

    static final String VENDA_STOP = "{\"id\":\"88ecb117-8ebf-31d3-a9d7-aa13cf4434e4\",\"tipo\":\"ORDEM_ADMITIDA\",\"versao\":3,"
            + "\"origem\":\"middle-office\",\"correlacao\":\"00000000-0000-0000-0000-0000000000a3\","
            + "\"ocorridoEm\":\"2026-10-18T12:00:00Z\",\"conta\":\"CONTA-003\",\"traceparent\":null,"
            + "\"dados\":{\"ticker\":\"ALFA3\",\"lado\":\"VENDA\",\"dataSessao\":\"2026-10-18\",\"precoMaximo\":null,"
            + "\"pernas\":[{\"papel\":\"TAKE_PROFIT\",\"ordem\":\"00000000-0000-0000-0000-0000000000b3\",\"tipo\":\"LIMIT\","
            + "\"validade\":\"DIA\",\"quantidade\":3,\"precoLimite\":\"10.20\"},{\"papel\":\"STOP_LOSS\","
            + "\"ordem\":\"00000000-0000-0000-0000-0000000000c3\",\"tipo\":\"STOP\",\"validade\":\"DIA\",\"quantidade\":3,"
            + "\"precoLimite\":null,\"precoGatilho\":\"9.80\"}]}}";

    static UUID id(String sufixo) {
        return UUID.fromString("00000000-0000-0000-0000-0000000000" + sufixo);
    }

    static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void compraComStopDoMoViraParComPrecoMaximoComum() {
        var c = EnvelopeMatching.comando(bytes(COMPRA_STOP), "ALFA3");
        assertTrue(c.oco());
        assertEquals(TRACE, c.traceparent());
        var tp = c.admissao();
        var sl = c.stopLoss();
        assertEquals(id("b1"), tp.ordem());
        assertEquals(id("c1"), sl.ordem());
        assertEquals(id("a1"), tp.intencao());
        assertEquals(id("a1"), sl.intencao());
        assertEquals(TipoOrdem.LIMIT, tp.tipo());
        assertEquals(TipoOrdem.STOP, sl.tipo());
        assertEquals(Validade.DIA, sl.validade());
        assertEquals(Lado.COMPRA, sl.lado());
        assertEquals(2, tp.quantidade());
        assertEquals(2, sl.quantidade());
        assertEquals(new BigDecimal("11.22"), tp.precoMaximo());
        assertEquals(new BigDecimal("11.22"), sl.precoMaximo());
        assertEquals(new BigDecimal("9.80"), tp.precoLimite());
        assertNull(sl.precoLimite());
        assertEquals(new BigDecimal("10.20"), sl.precoGatilho());
    }

    @Test
    void compraComStopLimitEVendaDoMoSaoAceitas() {
        var limite = EnvelopeMatching.comando(bytes(COMPRA_STOP_LIMIT), "ALFA3");
        assertEquals(TipoOrdem.STOP_LIMIT, limite.stopLoss().tipo());
        assertEquals(new BigDecimal("10.30"), limite.stopLoss().precoLimite());
        assertEquals(new BigDecimal("10.30"), limite.admissao().precoMaximo());
        var venda = EnvelopeMatching.comando(bytes(VENDA_STOP), "ALFA3");
        assertEquals(Lado.VENDA, venda.admissao().lado());
        assertNull(venda.admissao().precoMaximo());
        assertNull(venda.stopLoss().precoMaximo());
        assertEquals("CONTA-003", venda.stopLoss().conta());
        assertEquals(3, venda.stopLoss().quantidade());
    }

    @Test
    void parDeCompraDoMoExecutaAlvoECancelaStopComoNaPlanilha() throws Exception {
        var s = new SessaoMatching();
        var admissao = processar(s, bytes(COMPRA_STOP));
        assertEquals(List.of("ORDEM_ACEITA", "ORDEM_ACEITA", "OFERTA_NO_LIVRO", "STOP_PENDENTE"), tipos(admissao));
        var venda = processar(s, comando("vendaAlvo", "LIMIT", "VENDA", 2, "9.80", null));
        assertEquals(List.of("ORDEM_ACEITA", "NEGOCIO", "ORDEM_ENCERRADA", "ORDEM_ENCERRADA", "ORDEM_ENCERRADA"),
                tipos(venda));
        JsonNode cancelada = venda.stream()
                .filter(e -> e.path("dados").path("motivo").asText().equals("CANCELADA_OCO")).findFirst().orElseThrow();
        assertEquals(id("c1").toString(), cancelada.path("dados").path("ordem").asText());
        assertEquals(0, cancelada.path("dados").path("executada").asLong());
        assertEquals(2, cancelada.path("dados").path("cancelada").asLong());
        assertTrue(venda.stream().noneMatch(e -> e.path("tipo").asText().equals("ORDEM_REDUZIDA")),
                "execução total do TP encerra o SL sem redução");
    }

    private static List<String> tipos(List<JsonNode> eventos) {
        return eventos.stream().map(e -> e.path("tipo").asText()).toList();
    }
}
