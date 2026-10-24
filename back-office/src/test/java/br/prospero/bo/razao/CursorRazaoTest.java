package br.prospero.bo.razao;

import br.prospero.bo.razao.dominio.ConsultaInvalidaException;
import br.prospero.bo.razao.helper.CursorRazao;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class CursorRazaoTest {
    @Test
    void cursorEOpacoSemContaLegivelERetornaASequencia() {
        String cursor = CursorRazao.codificar("razao", "CONTA-002", 42);
        assertTrue(cursor.matches("[A-Za-z0-9_-]{1,120}"), "base64url sem preenchimento");
        String claro = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        assertFalse(claro.contains("CONTA"), "a conta não viaja no cursor");
        assertEquals(42, CursorRazao.decodificar("razao", "CONTA-002", cursor));
        assertEquals(Long.MAX_VALUE, CursorRazao.decodificar("negocios", "CONTA-001",
                CursorRazao.codificar("negocios", "CONTA-001", Long.MAX_VALUE)));
    }

    @Test
    void cursorDeOutraContaOuDeOutraListagemEInvalido() {
        String cursor = CursorRazao.codificar("razao", "CONTA-002", 42);
        assertThrows(ConsultaInvalidaException.class, () -> CursorRazao.decodificar("razao", "CONTA-003", cursor));
        assertThrows(ConsultaInvalidaException.class, () -> CursorRazao.decodificar("negocios", "CONTA-002", cursor));
    }

    @Test
    void cursorAdulteradoMalformadoOuExcessivoEInvalidoSemEcoarEntrada() {
        String cursor = CursorRazao.codificar("razao", "CONTA-002", 42);
        String claro = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        String outroId = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(claro.replace(".42.", ".41.").getBytes(StandardCharsets.UTF_8));
        for (String invalido : new String[]{outroId, cursor.substring(1), cursor + "A", "", "%%%", "a.b.c",
                "x".repeat(121), Base64.getUrlEncoder().encodeToString("v1.razao.0.0000000000000000".getBytes()),
                Base64.getUrlEncoder().withoutPadding().encodeToString("v1.razao.-1.0".getBytes())}) {
            var erro = assertThrows(ConsultaInvalidaException.class,
                    () -> CursorRazao.decodificar("razao", "CONTA-002", invalido), invalido);
            assertEquals("CONSULTA_INVALIDA", erro.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> CursorRazao.codificar("razao", "CONTA-002", 0));
    }
}
