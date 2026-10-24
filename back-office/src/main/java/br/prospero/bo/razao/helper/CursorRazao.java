package br.prospero.bo.razao.helper;

import br.prospero.bo.posicoes.helper.EnvelopeMatching;
import br.prospero.bo.razao.dominio.ConsultaInvalidaException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Cursor opaco (base64url de "v1.tipo.sequencia.vinculo") da paginação decrescente. A conta da consulta vem sempre
 * do token; o vínculo é um resumo de tipo/conta/sequência que só detecta cursor de outra conta ou listagem e
 * adulteração acidental, sem servir de autoridade. Qualquer divergência é a mesma recusa genérica.
 */
public final class CursorRazao {
    private static final int TAMANHO_MAXIMO = 120;
    private static final Pattern FORMA = Pattern.compile("v1\\.(negocios|razao)\\.([1-9][0-9]{0,18})\\.([0-9a-f]{16})");

    private CursorRazao() { }

    public static String codificar(String tipo, String conta, long sequencia) {
        if (sequencia < 1) throw new IllegalArgumentException("Sequência inválida.");
        String texto = "v1." + tipo + "." + sequencia + "." + vinculo(tipo, conta, sequencia);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }

    public static long decodificar(String tipo, String conta, String opaco) {
        if (opaco == null || opaco.isEmpty() || opaco.length() > TAMANHO_MAXIMO || !opaco.matches("[A-Za-z0-9_-]+")) {
            throw new ConsultaInvalidaException();
        }
        try {
            var forma = FORMA.matcher(new String(Base64.getUrlDecoder().decode(opaco), StandardCharsets.UTF_8));
            if (!forma.matches() || !forma.group(1).equals(tipo)) throw new ConsultaInvalidaException();
            long sequencia = Long.parseLong(forma.group(2));
            if (!vinculo(tipo, conta, sequencia).equals(forma.group(3))) throw new ConsultaInvalidaException();
            return sequencia;
        } catch (IllegalArgumentException erro) {
            throw new ConsultaInvalidaException();
        }
    }

    private static String vinculo(String tipo, String conta, long sequencia) {
        return EnvelopeMatching.assinatura(("cursor|" + tipo + "|" + conta + "|" + sequencia)
                .getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }
}
