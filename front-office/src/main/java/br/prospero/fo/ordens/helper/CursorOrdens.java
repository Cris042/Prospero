package br.prospero.fo.ordens.helper;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.dominio.RegraNegocioException;
import br.prospero.fo.ordens.dominio.RepositorioOrdens.Cursor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/** Cursor opaco (base64url de "segundos.nanos.uuid") da paginação por recebimento decrescente. */
public final class CursorOrdens {
    private static final Pattern FORMA = Pattern.compile("-?[0-9]{1,12}\\.[0-9]{1,9}\\.[0-9a-f-]{36}");

    private CursorOrdens() {
    }

    public static String codificar(Cursor cursor) {
        String texto = cursor.recebidaEm().getEpochSecond() + "." + cursor.recebidaEm().getNano() + "." + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decodificar(String opaco) {
        if (opaco == null) {
            return null;
        }
        try {
            String texto = new String(Base64.getUrlDecoder().decode(opaco), StandardCharsets.UTF_8);
            if (opaco.length() > 120 || !FORMA.matcher(texto).matches()) {
                throw invalido();
            }
            String[] partes = texto.split("\\.", 3);
            return new Cursor(Instant.ofEpochSecond(Long.parseLong(partes[0]), Long.parseLong(partes[1])),
                    UUID.fromString(partes[2]));
        } catch (IllegalArgumentException erro) {
            throw invalido();
        }
    }

    private static RegraNegocioException invalido() {
        return new RegraNegocioException(Categoria.ENTRADA_INVALIDA, "CURSOR_INVALIDO", "Cursor de paginação inválido.");
    }
}
