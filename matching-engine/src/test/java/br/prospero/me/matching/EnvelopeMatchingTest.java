package br.prospero.me.matching;

import br.prospero.me.matching.helper.EnvelopeMatching;
import br.prospero.me.matching.helper.SessaoMatching;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EnvelopeMatchingTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static byte[] comando(UUID ordem, UUID intencao, String lado, String preco, String sessao) {
        return ("{\"id\":\"" + UUID.nameUUIDFromBytes(("admissao:" + intencao).getBytes(StandardCharsets.UTF_8))
                + "\",\"tipo\":\"ORDEM_ADMITIDA\",\"versao\":1,\"origem\":\"middle-office\",\"correlacao\":\"" + intencao
                + "\",\"ocorridoEm\":\"2026-10-18T12:00:00Z\",\"conta\":\"CONTA-001\",\"traceparent\":null,\"dados\":{\"ordem\":\"" + ordem
                + "\",\"ticker\":\"ALFA3\",\"lado\":\"" + lado + "\",\"tipo\":\"LIMIT\",\"validade\":\"DIA\",\"quantidade\":10,\"precoLimite\":\"" + preco
                + "\",\"precoMaximo\":" + (lado.equals("COMPRA") ? "\"" + preco + "\"" : "null")
                + ",\"dataSessao\":\"" + sessao + "\"}}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void rejeitaExtraDuplicataEPrecoNaoCanonico() {
        byte[] valido = comando(UUID.randomUUID(), UUID.randomUUID(), "VENDA", "10.00", "2026-10-18");
        assertDoesNotThrow(() -> EnvelopeMatching.comando(valido, "ALFA3"));
        assertThrows(IllegalArgumentException.class, () -> EnvelopeMatching.comando(valido, "BETA3"));
        String original = new String(valido, StandardCharsets.UTF_8);
        for (String invalido : new String[]{original.replace("\"versao\":1", "\"versao\":1,\"extra\":1"),
                original.replace("\"versao\":1", "\"versao\":1,\"versao\":1"),
                original.replace("10.00", "10.0"), original + " {}"}) {
            assertThrows(IllegalArgumentException.class, () -> EnvelopeMatching.comando(invalido.getBytes(StandardCharsets.UTF_8), "ALFA3"));
        }
    }

    @Test
    void replayProduzMesmosIdsEEnriquecePassiva() throws Exception {
        UUID venda = UUID.randomUUID(), compra = UUID.randomUUID(), intencaoVenda = UUID.randomUUID();
        var a = EnvelopeMatching.comando(comando(venda, intencaoVenda, "VENDA", "10.00", "2026-10-18"), "ALFA3");
        var b = EnvelopeMatching.comando(comando(compra, UUID.randomUUID(), "COMPRA", "10.00", "2026-10-18"), "ALFA3");
        SessaoMatching motor = new SessaoMatching(), replay = new SessaoMatching();
        motor.processar(a); replay.processar(a);
        var eventos = motor.processar(b);
        var repetidos = replay.processar(b);
        assertEquals(eventos.size(), repetidos.size());
        for (int i = 0; i < eventos.size(); i++) assertArrayEquals(eventos.get(i), repetidos.get(i));
        var negocio = JSON.readTree(eventos.get(1));
        assertEquals(intencaoVenda.toString(), negocio.path("dados").path("intencaoVenda").asText());
        assertEquals(b.id().toString(), negocio.path("correlacao").asText());
        assertTrue(motor.processar(b).isEmpty());
    }

    @Test
    void recusaTrocaSessaoAntesDeMutar() {
        SessaoMatching motor = new SessaoMatching();
        motor.processar(EnvelopeMatching.comando(comando(UUID.randomUUID(), UUID.randomUUID(), "VENDA", "10.00", "2026-10-18"), "ALFA3"));
        assertThrows(IllegalArgumentException.class, () -> motor.processar(EnvelopeMatching.comando(
                comando(UUID.randomUUID(), UUID.randomUUID(), "COMPRA", "10.00", "2026-10-19"), "ALFA3")));
    }
}
