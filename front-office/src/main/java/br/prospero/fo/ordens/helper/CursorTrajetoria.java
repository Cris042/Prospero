package br.prospero.fo.ordens.helper;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.dominio.RegraNegocioException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Cursor limita paginação; autorização permanece na consulta por conta e ordem. */
public final class CursorTrajetoria {
    private CursorTrajetoria() { }

    public static String codificar(String conta, UUID ordem, long sequencia) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (conta + ":" + ordem + ":" + sequencia).getBytes(StandardCharsets.UTF_8));
    }

    public static long decodificar(String cursor, String conta, UUID ordem) {
        if (cursor == null) return 0;
        try {
            if (cursor.length() > 160 || !cursor.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            String[] partes = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
            if (partes.length != 3 || !conta.equals(partes[0]) || !ordem.toString().equals(partes[1])
                    || !partes[2].matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
            return Long.parseLong(partes[2]);
        } catch (IllegalArgumentException erro) {
            throw new RegraNegocioException(Categoria.ENTRADA_INVALIDA, "CURSOR_INVALIDO", "Cursor de paginação inválido.");
        }
    }
}
