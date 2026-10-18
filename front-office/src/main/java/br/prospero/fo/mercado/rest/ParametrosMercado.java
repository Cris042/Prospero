package br.prospero.fo.mercado.rest;

import br.prospero.fo.mercado.dominio.RegraMercadoException;

import java.util.UUID;
import java.util.regex.Pattern;

/** Conversão de namespace e ticker das rotas de mercado. */
final class ParametrosMercado {
    static final UUID NAMESPACE_PRINCIPAL = new UUID(0L, 0L);
    private static final Pattern TICKER = Pattern.compile("[A-Z][A-Z0-9]{3,7}");
    private static final Pattern UUID_TEXTO = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private ParametrosMercado() {
    }

    static UUID namespace(String texto, boolean obrigatorio) {
        if (texto == null && !obrigatorio) {
            return NAMESPACE_PRINCIPAL;
        }
        if (texto == null || !UUID_TEXTO.matcher(texto).matches()) {
            throw new RegraMercadoException("NAMESPACE_INVALIDO", "Namespace deve ser UUID em minúsculas.");
        }
        return UUID.fromString(texto);
    }

    static String ticker(String texto) {
        if (texto == null || !TICKER.matcher(texto).matches()) {
            throw new RegraMercadoException("ATIVO_DESCONHECIDO", "Ativo não cadastrado.");
        }
        return texto;
    }
}
