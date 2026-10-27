package br.prospero.fo.atualizacoes;

import br.prospero.fo.atualizacoes.helper.OrigemAtualizacoes;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrigemAtualizacoesTest {
    @Test
    void aceitaOrigemHttpLocalComMesmaAuthorityDoHost() {
        assertTrue(OrigemAtualizacoes.permitida("http://localhost:4200", "localhost:4200"));
        assertTrue(OrigemAtualizacoes.permitida("http://127.0.0.1:8080", "127.0.0.1:8080"));
        assertTrue(OrigemAtualizacoes.permitida("http://localhost", "localhost"));
    }

    @Test
    void recusaPortaHostProtocoloEOuOrigemAusenteDivergenteOuMalformada() {
        for (String origem : new String[]{null, "", "null", "https://localhost:4200", "http://localhost:4201",
                "http://127.0.0.1:4200", "http://localhost.evil:4200", "http://evil:4200", "http://usuario@localhost:4200",
                "http://localhost:4200/rota", "http://localhost:4200?x=1", "http://localhost:4200#x",
                "http://localhost:4200, http://evil", "http://localhost:4200\r\nX: y"}) {
            assertFalse(OrigemAtualizacoes.permitida(origem, "localhost:4200"), String.valueOf(origem));
        }
        assertFalse(OrigemAtualizacoes.permitida("http://localhost:4200", null));
        assertFalse(OrigemAtualizacoes.permitida("http://localhost:4200", "localhost:4200.evil"));
        assertFalse(OrigemAtualizacoes.permitida("http://localhost:80", "localhost"));
    }
}
