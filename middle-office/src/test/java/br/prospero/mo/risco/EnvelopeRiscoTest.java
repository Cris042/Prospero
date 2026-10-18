package br.prospero.mo.risco;

import br.prospero.mo.risco.dominio.ComandoRisco;
import br.prospero.mo.risco.dominio.Decisao;
import br.prospero.mo.risco.dominio.Lado;
import br.prospero.mo.risco.dominio.MotivoRejeicao;
import br.prospero.mo.risco.dominio.TipoOrdem;
import br.prospero.mo.risco.helper.EnvelopeInvalidoException;
import br.prospero.mo.risco.helper.EnvelopeRisco;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contrato estrito do comando (ADR-012): nada fora dele chega ao domínio. */
class EnvelopeRiscoTest {
    static final String PERNA = """
            {"id":"%s","tipo":"LIMIT","quantidade":100,"precoLimite":"10.10","precoGatilho":null}"""
            .formatted(UUID.randomUUID());

    static String envelope(String origem, String pernas) {
        return """
                {"id":"%s","tipo":"AVALIAR_RISCO","versao":1,"origem":"%s","correlacao":"%s",
                 "ocorridoEm":"2026-10-18T12:00:00Z","conta":"CONTA-002",
                 "traceparent":"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                 "dados":{"ticker":"ALFA3","lado":"COMPRA","precoBase":"10.00","ordens":[%s]}}"""
                .formatted(UUID.randomUUID(), origem, UUID.randomUUID(), pernas);
    }

    static ComandoRisco ler(String json) {
        return EnvelopeRisco.comando(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void leComandoValido() {
        ComandoRisco c = ler(envelope("front-office", PERNA));
        assertEquals("CONTA-002", c.conta());
        assertEquals(Lado.COMPRA, c.lado());
        assertEquals(new BigDecimal("10.00"), c.precoBase());
        assertEquals(TipoOrdem.LIMIT, c.pernas().getFirst().tipo());
        assertEquals(new BigDecimal("10.10"), c.pernas().getFirst().precoLimite());
        assertNull(c.pernas().getFirst().precoGatilho());
    }

    @Test
    void recusaTudoForaDoContrato() {
        String ok = envelope("front-office", PERNA);
        String[] invalidos = {
                envelope("middle-office", PERNA),
                ok.replace("\"AVALIAR_RISCO\"", "\"RESULTADO_RISCO\""),
                ok.replace("\"versao\":1", "\"versao\":2"),
                ok.replace("\"versao\":1", "\"versao\":\"1\""),
                ok.replace("\"versao\":1", "\"versao\":1,\"versao\":1"),
                ok.replace("\"conta\":\"CONTA-002\"", "\"conta\":\"CONTA-002\",\"extra\":true"),
                ok.replace("CONTA-002", "../x"),
                ok.replace("\"10.10\"", "10.10"),
                ok.replace("\"10.10\"", "\"10.105\""),
                ok.replace("\"10.10\"", "\"-1\""),
                ok.replace("\"10.10\"", "\"0\""),
                ok.replace("\"10.10\"", "\"1000000.01\""),
                ok.replace("\"quantidade\":100", "\"quantidade\":0"),
                ok.replace("\"quantidade\":100", "\"quantidade\":1.5"),
                ok.replace("\"quantidade\":100", "\"quantidade\":1000001"),
                ok.replace("\"LIMIT\"", "\"MARKET\""),
                ok.replace("\"LIMIT\"", "\"ICEBERG\""),
                envelope("front-office", PERNA + "," + PERNA + "," + PERNA),
                envelope("front-office", ""),
                ok.replace("2026-10-18T12:00:00Z", "ontem"),
                ok.replace("-01\"", "-01x\""),
                "{", "null", "x".repeat(EnvelopeRisco.TAMANHO_MAXIMO + 1)};
        for (String invalido : invalidos) {
            assertThrows(EnvelopeInvalidoException.class, () -> ler(invalido), invalido);
        }
    }

    @Test
    void resultadoTemValoresComoTexto() throws Exception {
        UUID id = UUID.randomUUID();
        Decisao rejeitada = new Decisao(UUID.randomUUID(), UUID.randomUUID(), "CONTA-002", "ALFA3", Lado.COMPRA,
                br.prospero.mo.risco.dominio.ResultadoRisco.REJEITADA, MotivoRejeicao.SALDO_INSUFICIENTE, null, null,
                1, Instant.EPOCH);
        JsonNode json = new ObjectMapper().readTree(EnvelopeRisco.resultado(id, rejeitada, Instant.EPOCH, null));
        assertEquals(id.toString(), json.get("id").asText());
        assertEquals("middle-office", json.get("origem").asText());
        assertEquals(rejeitada.intencao().toString(), json.get("correlacao").asText());
        assertEquals("SALDO_INSUFICIENTE", json.at("/dados/motivo").asText());
        assertTrue(json.at("/dados/valorReservado").isNull());
        Decisao reservada = new Decisao(UUID.randomUUID(), UUID.randomUUID(), "CONTA-002", "ALFA3", Lado.COMPRA,
                br.prospero.mo.risco.dominio.ResultadoRisco.RESERVADA, null, new BigDecimal("1010.00"), null, 1,
                Instant.EPOCH);
        json = new ObjectMapper().readTree(EnvelopeRisco.resultado(id, reservada, Instant.EPOCH, null));
        assertTrue(json.at("/dados/valorReservado").isTextual());
        assertEquals("1010.00", json.at("/dados/valorReservado").asText());
    }
}
