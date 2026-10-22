package br.prospero.fo.ordens;

import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Bytes reais emitidos pelo EnvelopeMatching do ME (RF05c.4b), fixados em EncerramentoQaTest.bytesReaisCopiadosParaOsContratosDoMoEFo do ME. */
class EncerramentoBytesMeTest {
    static final UUID CAUSACAO = UUID.nameUUIDFromBytes("encerramento:ALFA3:2026-10-18".getBytes(StandardCharsets.UTF_8));
    /** Oferta Limit expirada (v1 na admissão), Stop pendente (v2), duas pernas OCO (v3), marco ALFA3 e marco BETA3 vazio. */
    static final List<String> EVENTOS = List.of(
            "{\"id\":\"35208740-8811-386c-acd4-89c90de0072d\",\"versao\":4,\"origem\":\"matching-engine\",\"correlacao\":\"7a851961-bdc0-38e6-8d2c-bbc15425b493\",\"ocorridoEm\":\"2026-10-18T21:00:00Z\",\"ticker\":\"ALFA3\",\"dataSessao\":\"2026-10-18\",\"traceparent\":null,\"causacao\":\"f8e7dad5-395f-3b04-aa8a-19a234cc3bb2\",\"dados\":{\"ordem\":\"570f8e40-738a-3bca-9d84-1bfeb6bfc05f\",\"intencao\":\"57ca3bea-aa20-33ff-ac90-aa7a3247c7d9\",\"conta\":\"CONTA-001\",\"motivo\":\"EXPIRADA_DIA\",\"executada\":0,\"cancelada\":2},\"tipo\":\"ORDEM_ENCERRADA\"}",
            "{\"id\":\"20077cff-6356-37bc-979c-abd203a0c74f\",\"versao\":4,\"origem\":\"matching-engine\",\"correlacao\":\"ee6dc299-247e-3fa7-a330-acc393b199a3\",\"ocorridoEm\":\"2026-10-18T21:00:00Z\",\"ticker\":\"ALFA3\",\"dataSessao\":\"2026-10-18\",\"traceparent\":null,\"causacao\":\"f8e7dad5-395f-3b04-aa8a-19a234cc3bb2\",\"dados\":{\"ordem\":\"f2f530ee-f36a-3d56-b6f7-2e5528a6e2dd\",\"intencao\":\"4e27e0d6-28ef-361a-aaf3-e3f1f7f38495\",\"conta\":\"CONTA-001\",\"motivo\":\"EXPIRADA_DIA\",\"executada\":0,\"cancelada\":2},\"tipo\":\"ORDEM_ENCERRADA\"}",
            "{\"id\":\"a3db2046-79e0-3f63-a2f7-627c66febb36\",\"versao\":4,\"origem\":\"matching-engine\",\"correlacao\":\"d45567d1-1445-3824-9d79-c37402194069\",\"ocorridoEm\":\"2026-10-18T21:00:00Z\",\"ticker\":\"ALFA3\",\"dataSessao\":\"2026-10-18\",\"traceparent\":null,\"causacao\":\"f8e7dad5-395f-3b04-aa8a-19a234cc3bb2\",\"dados\":{\"ordem\":\"69347f7f-e35d-3806-8ca8-9e04421c7f94\",\"intencao\":\"3dffd47e-bb98-3661-9ed0-7ceaed9e71d5\",\"conta\":\"CONTA-002\",\"motivo\":\"EXPIRADA_DIA\",\"executada\":0,\"cancelada\":4},\"tipo\":\"ORDEM_ENCERRADA\"}",
            "{\"id\":\"dd95da59-f952-31e6-88a1-3185a9648f0a\",\"versao\":4,\"origem\":\"matching-engine\",\"correlacao\":\"d45567d1-1445-3824-9d79-c37402194069\",\"ocorridoEm\":\"2026-10-18T21:00:00Z\",\"ticker\":\"ALFA3\",\"dataSessao\":\"2026-10-18\",\"traceparent\":null,\"causacao\":\"f8e7dad5-395f-3b04-aa8a-19a234cc3bb2\",\"dados\":{\"ordem\":\"a86d6ea9-493b-3d7f-9940-bc9ac532e6e1\",\"intencao\":\"3dffd47e-bb98-3661-9ed0-7ceaed9e71d5\",\"conta\":\"CONTA-002\",\"motivo\":\"EXPIRADA_DIA\",\"executada\":0,\"cancelada\":4},\"tipo\":\"ORDEM_ENCERRADA\"}",
            "{\"id\":\"b28c068a-92ea-3243-83e9-42ddfb7922e9\",\"versao\":4,\"origem\":\"matching-engine\",\"correlacao\":\"f8e7dad5-395f-3b04-aa8a-19a234cc3bb2\",\"ocorridoEm\":\"2026-10-18T21:00:00Z\",\"ticker\":\"ALFA3\",\"dataSessao\":\"2026-10-18\",\"traceparent\":null,\"causacao\":\"f8e7dad5-395f-3b04-aa8a-19a234cc3bb2\",\"dados\":{\"expiradas\":4},\"tipo\":\"SESSAO_ENCERRADA\"}",
            "{\"id\":\"7e5f905a-fd79-327f-b96d-b9bd48cc657f\",\"versao\":4,\"origem\":\"matching-engine\",\"correlacao\":\"4e536858-c741-325a-82a1-c030cc944dcb\",\"ocorridoEm\":\"2026-10-18T21:00:00Z\",\"ticker\":\"BETA3\",\"dataSessao\":\"2026-10-18\",\"traceparent\":null,\"causacao\":\"4e536858-c741-325a-82a1-c030cc944dcb\",\"dados\":{\"expiradas\":0},\"tipo\":\"SESSAO_ENCERRADA\"}");

    @Test
    void foAceitaOsBytesReaisDoMe() {
        for (int i = 0; i < EVENTOS.size(); i++) {
            String json = EVENTOS.get(i);
            String chave = i == 5 ? "BETA3" : "ALFA3";
            EventoMatching e = EnvelopeMatching.evento(json.getBytes(StandardCharsets.UTF_8), chave);
            assertEquals(4, e.versao());
            assertEquals(EventoMatching.encerramento(e.ticker(), e.sessao()), e.causacao());
            if (i < 4) {
                var encerrada = assertInstanceOf(EventoMatching.Encerrada.class, e.detalhe());
                assertEquals("EXPIRADA_DIA", String.valueOf(encerrada.motivo()));
            }
        }
        var marco = EnvelopeMatching.evento(EVENTOS.get(4).getBytes(StandardCharsets.UTF_8), "ALFA3");
        assertEquals(CAUSACAO, marco.correlacao());
        assertEquals(4, assertInstanceOf(EventoMatching.SessaoEncerrada.class, marco.detalhe()).expiradas());
        var vazio = EnvelopeMatching.evento(EVENTOS.get(5).getBytes(StandardCharsets.UTF_8), "BETA3");
        assertEquals(0, assertInstanceOf(EventoMatching.SessaoEncerrada.class, vazio.detalhe()).expiradas());
    }
}
