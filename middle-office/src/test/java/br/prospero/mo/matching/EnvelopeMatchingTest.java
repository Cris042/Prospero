package br.prospero.mo.matching;

import br.prospero.mo.matching.helper.EnvelopeMatching;
import br.prospero.mo.mensageria.dominio.MensagemInvalidaException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class EnvelopeMatchingTest {
    private static final String EVENTO="""
            {"id":"80ef3249-7815-4560-a260-63ecf28bd581","tipo":"NEGOCIO","versao":1,"origem":"matching-engine",
            "correlacao":"e4b8a895-e56a-4430-925b-29236393db61","ocorridoEm":"2026-10-18T12:00:00Z",
            "ticker":"ALFA3","dataSessao":"2026-10-18","traceparent":null,
            "dados":{"negocio":"ALFA3-2026-10-18-1","preco":"10.00","quantidade":2,
            "ordemCompra":"e4b8a895-e56a-4430-925b-29236393db61","intencaoCompra":"e4b8a895-e56a-4430-925b-29236393db61","contaCompra":"CONTA-001",
            "ordemVenda":"ed42a1e3-9ef9-4ba5-87b4-a0bcf224a316","intencaoVenda":"ed42a1e3-9ef9-4ba5-87b4-a0bcf224a316","contaVenda":"CONTA-002","agressor":"COMPRA"}}
            """;
    @Test void aceitaContratoERecusaCoercoesECamposExtras() {
        assertEquals("NEGOCIO",EnvelopeMatching.ler(EVENTO.getBytes(StandardCharsets.UTF_8)).get("tipo").textValue());
        for(String s:java.util.List.of(EVENTO.replace("\"quantidade\":2","\"quantidade\":2.0"),EVENTO.replace("10.00","10.001"),
                EVENTO.replace("\"versao\":1","\"versao\":2"),EVENTO.replace("\"agressor\":\"COMPRA\"","\"agressor\":\"VENDEDOR\""),
                EVENTO.replace("\"versao\":1","\"versao\":1,\"extra\":true"),EVENTO.replace("\"versao\":1","\"versao\":1,\"versao\":1"))) {
            assertThrows(MensagemInvalidaException.class,()->EnvelopeMatching.ler(s.getBytes(StandardCharsets.UTF_8)));
        }
    }
    @Test void assinaturaIndependeDaOrdemDosCampos() throws Exception {
        var json=new ObjectMapper();
        assertEquals(EnvelopeMatching.assinatura(json.readTree("{\"a\":1,\"b\":2}")),EnvelopeMatching.assinatura(json.readTree("{\"b\":2,\"a\":1}")));
    }
    @Test void contasDoRoboEDeMedicaoEntramNaAllowlistEContaInexistenteContinuaRecusada() {
        assertEquals("NEGOCIO",EnvelopeMatching.ler(EVENTO.replace("\"contaVenda\":\"CONTA-002\"","\"contaVenda\":\"CONTA-004\"")
                .getBytes(StandardCharsets.UTF_8)).get("tipo").textValue());
        assertEquals("NEGOCIO",EnvelopeMatching.ler(EVENTO.replace("\"contaVenda\":\"CONTA-002\"","\"contaVenda\":\"CONTA-005\"")
                .getBytes(StandardCharsets.UTF_8)).get("tipo").textValue());
        assertThrows(MensagemInvalidaException.class,()->EnvelopeMatching.ler(EVENTO.replace("\"contaVenda\":\"CONTA-002\"",
                "\"contaVenda\":\"CONTA-999\"").getBytes(StandardCharsets.UTF_8)));
    }
}
