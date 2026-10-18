package br.prospero.me.matching;

import br.prospero.me.matching.helper.EnvelopeMatching;
import br.prospero.me.matching.helper.SessaoMatching;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SessaoMatchingTest {
    @Test
    void reentregaConflitanteBloqueiaAntesDeAlterarLivro() {
        UUID ordem = UUID.randomUUID(), intencao = UUID.randomUUID();
        byte[] venda = EnvelopeMatchingTest.comando(ordem, intencao, "VENDA", "10.00", "2026-10-18");
        SessaoMatching motor = new SessaoMatching();
        motor.processar(EnvelopeMatching.comando(venda, "ALFA3"));
        String alterada = new String(venda, StandardCharsets.UTF_8).replace("10.00", "9.00");
        assertThrows(IllegalArgumentException.class, () -> motor.processar(EnvelopeMatching.comando(alterada.getBytes(StandardCharsets.UTF_8), "ALFA3")));
        byte[] compra = EnvelopeMatchingTest.comando(UUID.randomUUID(), UUID.randomUUID(), "COMPRA", "10.00", "2026-10-18");
        String eventos = motor.processar(EnvelopeMatching.comando(compra, "ALFA3")).stream()
                .map(e -> new String(e, StandardCharsets.UTF_8)).reduce("", String::concat);
        assertTrue(eventos.contains("\"preco\":\"10.00\""));
        assertFalse(eventos.contains("\"preco\":\"9.00\""));
    }

    @Test
    void idDoEventoDerivaDaAdmissaoEIndiceMesmoAposReinicio() {
        UUID ordem = UUID.randomUUID(), intencao = UUID.randomUUID();
        var comando = EnvelopeMatching.comando(EnvelopeMatchingTest.comando(ordem, intencao, "VENDA", "10.00", "2026-10-18"), "ALFA3");
        var eventos = new SessaoMatching().processar(comando);
        for (int i = 0; i < eventos.size(); i++) {
            UUID esperado = UUID.nameUUIDFromBytes(("matching:" + comando.id() + ":" + i).getBytes(StandardCharsets.UTF_8));
            assertTrue(new String(eventos.get(i), StandardCharsets.UTF_8).contains("\"id\":\"" + esperado + "\""));
            assertTrue(eventos.get(i).length <= 16 * 1024);
        }
    }
}
