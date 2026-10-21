package br.prospero.mo.matching;

import br.prospero.mo.matching.helper.EnvelopeMatching;
import br.prospero.mo.mensageria.dominio.MensagemInvalidaException;
import br.prospero.mo.risco.dominio.*;
import br.prospero.mo.risco.helper.EnvelopeAdmissao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StopsContratoTest {
    static String evento(String tipo, String campos) {
        return """
                {"id":"80ef3249-7815-4560-a260-63ecf28bd581","tipo":"%s","versao":2,"origem":"matching-engine",
                "correlacao":"e4b8a895-e56a-4430-925b-29236393db61","causacao":"ed42a1e3-9ef9-4ba5-87b4-a0bcf224a316",
                "ocorridoEm":"2026-10-18T12:00:00Z","ticker":"ALFA3","dataSessao":"2026-10-18","traceparent":null,
                "dados":{"ordem":"e4b8a895-e56a-4430-925b-29236393db61","intencao":"e4b8a895-e56a-4430-925b-29236393db61","conta":"CONTA-002",%s}}
                """.formatted(tipo, campos);
    }
    @Test void admiteStopSimplesComSessaoEPreservaLegadoEOco() throws Exception {
        for (TipoOrdem tipo : List.of(TipoOrdem.STOP, TipoOrdem.STOP_LIMIT)) {
            var p = new Perna(UUID.randomUUID(), tipo, 2, tipo == TipoOrdem.STOP_LIMIT ? new BigDecimal("10.00") : null,
                    new BigDecimal("10.00"), Validade.DIA);
            var c = new ComandoRisco(UUID.randomUUID(), UUID.randomUUID(), "CONTA-002", "ALFA3", Lado.COMPRA,
                    new BigDecimal("10.00"), List.of(p), null, LocalDate.of(2026, 10, 18));
            assertTrue(c.admissivel(), "Stop novo com reserva pode ser admitido");
            var legado = new ComandoRisco(c.evento(), c.intencao(), c.conta(), c.ticker(), c.lado(), c.precoBase(), c.pernas(), null);
            assertFalse(legado.admissivel());
            var oco = new ComandoRisco(c.evento(), c.intencao(), c.conta(), c.ticker(), c.lado(), c.precoBase(), List.of(p,p), null, c.dataSessao());
            assertFalse(oco.admissivel());
        }
    }
    @Test void aceitaEventosV2ETerminaisNovosComCausacaoObrigatoria() {
        for (String s : List.of(evento("STOP_PENDENTE", "\"precoGatilho\":\"10.00\""),
                evento("STOP_ACIONADO", "\"tipoExecutavel\":\"MARKET\",\"sequencia\":2"),
                evento("STOP_ACIONADO", "\"tipoExecutavel\":\"LIMIT\",\"sequencia\":2"),
                evento("ORDEM_ENCERRADA", "\"motivo\":\"LIMITE_CASCATA\",\"executada\":0,\"cancelada\":2"),
                evento("ORDEM_REJEITADA", "\"motivo\":\"LIMITE_STOPS_PENDENTES\""))) {
            assertDoesNotThrow(() -> EnvelopeMatching.ler(s.getBytes(StandardCharsets.UTF_8)));
            assertThrows(MensagemInvalidaException.class, () -> EnvelopeMatching.ler(s.replace("\"versao\":2", "\"versao\":1").getBytes(StandardCharsets.UTF_8)));
        }
    }
    @Test void rejeitaV2SemCausacaoTipoExecutavelInvalidoEExpiracaoFutura() {
        String s=evento("STOP_ACIONADO", "\"tipoExecutavel\":\"MARKET\",\"sequencia\":2");
        for (String invalido : List.of(s.replace("\"tipoExecutavel\":\"MARKET\"", "\"tipoExecutavel\":\"STOP\""),
                s.replace("\"sequencia\":2", "\"sequencia\":0"), s.replace("\"causacao\":\"ed42a1e3-9ef9-4ba5-87b4-a0bcf224a316\",", ""),
                evento("ORDEM_ENCERRADA", "\"motivo\":\"EXPIRADA_DIA\",\"executada\":0,\"cancelada\":2"))) {
            assertThrows(MensagemInvalidaException.class, () -> EnvelopeMatching.ler(invalido.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
