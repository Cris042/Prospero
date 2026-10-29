package br.prospero.fo.comum.jpa;

import br.prospero.fo.comum.dominio.MensagemQuarentena;
import br.prospero.fo.comum.dominio.ResolucaoQuarentena;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-019 em PostgreSQL real: payload íntegro, uma linha por posição e resolução condicional. Opt-in. */
@QuarkusTest
@TestProfile(br.prospero.fo.ordens.MatchingPostgresIT.Banco.class)
class QuarentenaPostgresIT {
    @AfterAll
    static void removerSchemaProprio() throws Exception {
        br.prospero.fo.ordens.MatchingPostgresIT.removerSchemaProprio();
    }

    @Inject EventosRejeitadosJpa quarentena;

    @Test
    void registraUmaLinhaPorPosicaoComPayloadEResolveUmaVez() {
        byte[] payload = "{\"id\":\"x\",\"acentuação\":\"ç\"}".getBytes(StandardCharsets.UTF_8);
        var m = new MensagemQuarentena("risco.it.v1", 1, 77, "CONTA-001", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                payload, "contrato", 1);
        quarentena.registrar(m);
        quarentena.registrar(new MensagemQuarentena("risco.it.v1", 1, 77, "CONTA-001", null, null, "contrato", 10));
        var pendentes = quarentena.pendentes(50).stream().filter(i -> i.mensagem().topico().equals("risco.it.v1")).toList();
        assertEquals(1, pendentes.size(), "a reentrega da mesma posição não duplica a linha");
        var item = pendentes.getFirst();
        assertArrayEquals(payload, item.mensagem().payload(), "payload preservado quando a reentrega não o traz");
        assertEquals(11, item.mensagem().tentativas());
        assertEquals("CONTA-001", item.mensagem().chave());
        assertNotNull(item.quarentenadoEm());

        quarentena.falhou(item.id(), "reprocessamento: IllegalStateException");
        assertEquals(12, quarentena.buscar(item.id()).orElseThrow().mensagem().tentativas());
        assertTrue(quarentena.resolver(item.id(), ResolucaoQuarentena.REPROCESSADA));
        assertFalse(quarentena.resolver(item.id(), ResolucaoQuarentena.DESCARTADA), "só a primeira resolução vale");
        var resolvido = quarentena.buscar(item.id()).orElseThrow();
        assertEquals(ResolucaoQuarentena.REPROCESSADA, resolvido.resolucao());
        assertNotNull(resolvido.resolvidoEm());
        assertTrue(quarentena.pendentes(50).stream().noneMatch(i -> i.id() == item.id()));

        quarentena.registrar(m);
        assertTrue(quarentena.buscar(item.id()).orElseThrow().pendente(), "falha nova na mesma posição reabre a linha");
    }
}
