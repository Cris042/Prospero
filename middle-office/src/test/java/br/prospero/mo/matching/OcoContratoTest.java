package br.prospero.mo.matching;

import br.prospero.mo.matching.helper.EnvelopeMatching;
import br.prospero.mo.mensageria.dominio.MensagemInvalidaException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** RF05c.3b1: fatos OCO existem somente na v3; v1/v2 continuam recusando-os. */
class OcoContratoTest {
    private static byte[] v(String json, int versao) {
        return json.replace("\"versao\":2", "\"versao\":" + versao).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void reducaoECancelamentoOcoSoEmV3() {
        String reducao = StopsContratoTest.evento("ORDEM_REDUZIDA", "\"reduzida\":4,\"restante\":6");
        String cancelada = StopsContratoTest.evento("ORDEM_ENCERRADA",
                "\"motivo\":\"CANCELADA_OCO\",\"executada\":0,\"cancelada\":6");
        for (String json : List.of(reducao, cancelada)) {
            assertDoesNotThrow(() -> EnvelopeMatching.ler(v(json, 3)));
            assertThrows(MensagemInvalidaException.class, () -> EnvelopeMatching.ler(v(json, 2)));
        }
        assertThrows(MensagemInvalidaException.class, () -> EnvelopeMatching.ler(v(reducao.replace("\"reduzida\":4", "\"reduzida\":0"), 3)));
        assertThrows(MensagemInvalidaException.class, () -> EnvelopeMatching.ler(v(reducao.replace(",\"restante\":6", ""), 3)));
        assertThrows(MensagemInvalidaException.class, () -> EnvelopeMatching.ler(v(reducao, 4)));
    }

    @Test
    void v3ExigeCausacaoEAceitaFatosDeStopETerminaisDaCascata() {
        for (String json : List.of(StopsContratoTest.evento("STOP_PENDENTE", "\"precoGatilho\":\"10.00\""),
                StopsContratoTest.evento("ORDEM_ENCERRADA", "\"motivo\":\"LIMITE_CASCATA\",\"executada\":0,\"cancelada\":6"),
                StopsContratoTest.evento("ORDEM_REJEITADA", "\"motivo\":\"LIMITE_STOPS_PENDENTES\""))) {
            assertDoesNotThrow(() -> EnvelopeMatching.ler(v(json, 3)));
            String semCausa = json.replaceAll("\"causacao\":\"[^\"]+\",", "");
            assertThrows(MensagemInvalidaException.class, () -> EnvelopeMatching.ler(v(semCausa, 3)));
        }
    }
}
