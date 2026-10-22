package br.prospero.fo.ordens;

import br.prospero.fo.ordens.helper.EnvelopeMatching;
import br.prospero.fo.comum.dominio.MensagemInvalidaException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Forma esperada do JSON v4 que o ME emitirá na RF05c.4b (ainda inexistente): mesma raiz da v2/v3, na ordem de
 * campos do EnvelopeMatching do ME (tipo após dados), causação = encerramento:&lt;ticker&gt;:&lt;data&gt;.
 */
class EncerramentoContratoTest {
    private static final String CAUSACAO = UUID.nameUUIDFromBytes("encerramento:ALFA3:2026-10-18".getBytes(StandardCharsets.UTF_8)).toString();
    private static final String ORDEM = "80ef3249-7815-4560-a260-63ecf28bd581";

    private static String raiz(int versao, String correlacao, String causacao, String dados, String tipo) {
        return ("{\"id\":\"" + UUID.randomUUID() + "\",\"versao\":" + versao + ",\"origem\":\"matching-engine\",\"correlacao\":\""
                + correlacao + "\",\"ocorridoEm\":\"2026-10-18T21:00:00Z\",\"ticker\":\"ALFA3\",\"dataSessao\":\"2026-10-18\","
                + "\"traceparent\":null" + (causacao == null ? "" : ",\"causacao\":\"" + causacao + "\"") + ",\"dados\":" + dados
                + ",\"tipo\":\"" + tipo + "\"}");
    }

    private static String expiracao(String motivo) {
        return "{\"ordem\":\"" + ORDEM + "\",\"intencao\":\"" + ORDEM + "\",\"conta\":\"CONTA-002\",\"motivo\":\"" + motivo
                + "\",\"executada\":4,\"cancelada\":6}";
    }

    private static void ler(String json) {
        EnvelopeMatching.evento(json.getBytes(StandardCharsets.UTF_8), "ALFA3");
    }

    @Test
    void formaV4EsperadaDoMeEAceita() {
        assertDoesNotThrow(() -> ler(raiz(4, ORDEM, CAUSACAO, expiracao("EXPIRADA_DIA"), "ORDEM_ENCERRADA")));
        assertDoesNotThrow(() -> ler(raiz(4, CAUSACAO, CAUSACAO, "{\"expiradas\":0}", "SESSAO_ENCERRADA")), "livro vazio");
        assertDoesNotThrow(() -> ler(raiz(4, CAUSACAO, CAUSACAO, "{\"expiradas\":12}", "SESSAO_ENCERRADA")));
    }

    @Test
    void v4SoTransportaExpiracaoEMarco() {
        for (String motivo : new String[]{"EXECUTADA", "CANCELADA_OCO", "LIMITE_CASCATA", "SALDO_CANCELADO_IOC"}) {
            assertThrows(MensagemInvalidaException.class, () -> ler(raiz(4, ORDEM, CAUSACAO, expiracao(motivo), "ORDEM_ENCERRADA")), motivo);
        }
        String rejeicao = "{\"ordem\":\"" + ORDEM + "\",\"intencao\":\"" + ORDEM + "\",\"conta\":\"CONTA-002\",\"motivo\":\"SESSAO_ENCERRADA\"}";
        assertThrows(MensagemInvalidaException.class, () -> ler(raiz(4, ORDEM, CAUSACAO, rejeicao, "ORDEM_REJEITADA")), "rejeição em v4");
        assertDoesNotThrow(() -> ler(raiz(3, ORDEM, ORDEM, rejeicao, "ORDEM_REJEITADA")), "rejeição tardia em v3");
        assertThrows(MensagemInvalidaException.class, () -> ler(raiz(4, ORDEM, null, expiracao("EXPIRADA_DIA"), "ORDEM_ENCERRADA")), "v4 sem causação");
        assertThrows(MensagemInvalidaException.class, () -> ler(raiz(5, ORDEM, CAUSACAO, expiracao("EXPIRADA_DIA"), "ORDEM_ENCERRADA")), "v5");
    }

    @Test
    void expiracaoEMarcoForaDaV4SaoRecusados() {
        for (int versao = 1; versao <= 3; versao++) {
            String causacao = versao == 1 ? null : CAUSACAO;
            int v = versao;
            assertThrows(MensagemInvalidaException.class, () -> ler(raiz(v, ORDEM, causacao, expiracao("EXPIRADA_DIA"), "ORDEM_ENCERRADA")), "v" + v);
            assertThrows(MensagemInvalidaException.class, () -> ler(raiz(v, CAUSACAO, causacao, "{\"expiradas\":0}", "SESSAO_ENCERRADA")), "marco v" + v);
        }
    }

    @Test
    void marcoTemDadosFechados() {
        assertThrows(MensagemInvalidaException.class, () -> ler(raiz(4, CAUSACAO, CAUSACAO, "{\"expiradas\":-1}", "SESSAO_ENCERRADA")));
        assertThrows(MensagemInvalidaException.class, () -> ler(raiz(4, CAUSACAO, CAUSACAO, "{\"expiradas\":\"2\"}", "SESSAO_ENCERRADA")));
        assertThrows(MensagemInvalidaException.class, () -> ler(raiz(4, CAUSACAO, CAUSACAO,
                "{\"expiradas\":1,\"conta\":\"CONTA-002\"}", "SESSAO_ENCERRADA")), "campo extra");
        assertThrows(MensagemInvalidaException.class, () -> ler(raiz(4, CAUSACAO, CAUSACAO, "{}", "SESSAO_ENCERRADA")));
    }
}
