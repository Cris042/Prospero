package br.prospero.bo.liquidacao;

import br.prospero.bo.liquidacao.helper.EventoCredito;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Confere o contrato publicado sem dependência de implementação entre BO e MO. */
class EventoCreditoTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NEGOCIO = "ALFA3-2026-10-23-1";
    private static final UUID CAUSA = UUID.fromString("d3b65f40-9457-41c1-ac5b-12832440d7e4");
    private static final Instant INSTANTE = Instant.parse("2026-10-27T12:00:00Z");
    private static final LocalDate DATA = LocalDate.of(2026, 10, 27);

    @Test
    void identificadorDeterministicoPorNegocioELadoCoincideComContratoDaRf08c() {
        for (String lado : Set.of("COMPRADOR", "VENDEDOR")) {
            UUID esperado = UUID.nameUUIDFromBytes(("liquidacao:" + NEGOCIO + ":" + lado).getBytes(StandardCharsets.UTF_8));
            assertEquals(esperado, EventoCredito.id(NEGOCIO, lado));
            assertEquals(esperado, EventoCredito.id(NEGOCIO, lado));
        }
        assertNotEquals(EventoCredito.id(NEGOCIO, "COMPRADOR"), EventoCredito.id(NEGOCIO, "VENDEDOR"));
    }

    @Test
    void compradorPublicaCustodiaInteiraEVendedorCaixaEmCentavosSemContraparte() throws Exception {
        conferir("COMPRADOR", "CONTA-001", "100", "CUSTODIA");
        conferir("VENDEDOR", "CONTA-002", "2000.00", "CAIXA");
    }

    private static void conferir(String lado, String conta, String valor, String recurso) throws Exception {
        String payload = EventoCredito.criar(NEGOCIO, lado, conta, "ALFA3", valor, DATA, new EventoCredito.Origem(CAUSA, INSTANTE));
        JsonNode e = JSON.readTree(payload);
        assertEquals(Set.of("id", "tipo", "versao", "origem", "causacao", "ocorridoEm", "traceparent", "dados"), campos(e));
        assertEquals(EventoCredito.id(NEGOCIO, lado).toString(), e.path("id").asText());
        assertEquals("LIQUIDACAO_EFETIVADA", e.path("tipo").asText());
        assertTrue(e.path("versao").isInt());
        assertEquals(1, e.path("versao").intValue());
        assertEquals("back-office", e.path("origem").asText());
        assertEquals(CAUSA.toString(), e.path("causacao").asText());
        assertEquals(INSTANTE, Instant.parse(e.path("ocorridoEm").asText()));
        assertTrue(e.path("traceparent").isNull());
        JsonNode dados = e.path("dados");
        assertEquals(Set.of("negocio", "lado", "conta", "recurso", "ticker", "valor", "dataLiquidacao"), campos(dados));
        assertEquals(NEGOCIO, dados.path("negocio").asText());
        assertEquals(lado, dados.path("lado").asText());
        assertEquals(conta, dados.path("conta").asText());
        assertEquals(recurso, dados.path("recurso").asText());
        assertEquals("ALFA3", dados.path("ticker").asText());
        assertTrue(dados.path("valor").isTextual());
        assertEquals(valor, dados.path("valor").asText());
        assertEquals(DATA.toString(), dados.path("dataLiquidacao").asText());
        assertTrue(payload.getBytes(StandardCharsets.UTF_8).length <= 4096);
    }

    private static Set<String> campos(JsonNode objeto) {
        Set<String> nomes = new HashSet<>();
        objeto.fieldNames().forEachRemaining(nomes::add);
        return nomes;
    }
}
