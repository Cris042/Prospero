package br.prospero.bo.acompanhamento;

import br.prospero.bo.acompanhamento.helper.EventoContabilizacao;
import br.prospero.bo.posicoes.dominio.EventoMatching;
import br.prospero.bo.posicoes.dominio.LadoOrdem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventoContabilizacaoTest {
    @Test
    void identidadeDeterministicaDependeDoNegocioESegueNamespace() {
        String negocio = "ALFA3-2026-10-25-42";
        assertEquals(UUID.nameUUIDFromBytes(("acompanhamento:" + negocio).getBytes(StandardCharsets.UTF_8)),
                EventoContabilizacao.id(negocio));
        assertNotEquals(EventoContabilizacao.id(negocio), EventoContabilizacao.id(negocio + "1"));
    }

    @Test
    void confirmaContabilizacaoComEnvelopeEstritoSemPrecoEQuantidadeTextual() throws Exception {
        UUID evento = UUID.randomUUID(), compra = UUID.randomUUID(), venda = UUID.randomUUID();
        String negocio = "ALFA3-2026-10-25-42";
        var detalhe = new EventoMatching.Negocio(negocio, new BigDecimal("10.00"), Long.MAX_VALUE,
                new EventoMatching.Alvo(compra, UUID.randomUUID(), "CONTA-002"),
                new EventoMatching.Alvo(venda, UUID.randomUUID(), "CONTA-003"), LadoOrdem.COMPRA);
        var matching = new EventoMatching(evento, UUID.randomUUID(), "ALFA3", LocalDate.of(2026, 10, 25),
                "a".repeat(64), detalhe);
        Instant instante = Instant.parse("2026-10-25T13:25:00Z");
        var raiz = new ObjectMapper().readTree(EventoContabilizacao.criar(matching, instante));
        Set<String> campos = new HashSet<>();
        raiz.fieldNames().forEachRemaining(campos::add);
        assertEquals(Set.of("id", "tipo", "versao", "origem", "causacao", "ocorridoEm", "traceparent", "dados"), campos);
        assertEquals(EventoContabilizacao.id(negocio).toString(), raiz.path("id").asText());
        assertEquals("NEGOCIO_CONTABILIZADO", raiz.path("tipo").asText());
        assertEquals(1, raiz.path("versao").asInt());
        assertEquals("back-office", raiz.path("origem").asText());
        assertEquals(evento.toString(), raiz.path("causacao").asText());
        assertEquals(instante, Instant.parse(raiz.path("ocorridoEm").asText()));
        assertTrue(raiz.path("traceparent").isNull());
        var dados = raiz.path("dados");
        campos.clear();
        dados.fieldNames().forEachRemaining(campos::add);
        assertEquals(Set.of("negocio", "ticker", "quantidade", "ordemCompra", "contaCompra", "ordemVenda", "contaVenda"), campos);
        assertEquals(negocio, dados.path("negocio").asText());
        assertEquals("ALFA3", dados.path("ticker").asText());
        assertTrue(dados.path("quantidade").isTextual());
        assertEquals(Long.toString(Long.MAX_VALUE), dados.path("quantidade").asText());
        assertEquals(compra.toString(), dados.path("ordemCompra").asText());
        assertEquals(venda.toString(), dados.path("ordemVenda").asText());
        assertEquals("CONTA-002", dados.path("contaCompra").asText());
        assertEquals("CONTA-003", dados.path("contaVenda").asText());
    }
}
