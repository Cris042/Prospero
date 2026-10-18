package br.prospero.fo.ordens.rest;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.dominio.RegraNegocioException;

import java.util.UUID;
import java.util.regex.Pattern;

/** Conversão de cabeçalho de idempotência e parâmetros de caminho/consulta das rotas de ordens. */
final class RequisicaoOrdem {
    static final String CABECALHO_IDEMPOTENCIA = "X-Idempotency-Key";
    private static final Pattern CHAVE = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern UUID_TEXTO = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private RequisicaoOrdem() {
    }

    static String chave(String texto) {
        if (texto == null || !CHAVE.matcher(texto).matches()) {
            throw new RegraNegocioException(Categoria.ENTRADA_INVALIDA, "IDEMPOTENCIA_AUSENTE",
                    "Cabeçalho X-Idempotency-Key obrigatório: 8 a 64 caracteres [A-Za-z0-9_-].");
        }
        return texto;
    }

    /** Identificador malformado é tratado como inexistente para não diferenciar formatos. */
    static UUID id(String texto, String codigo) {
        if (texto == null || !UUID_TEXTO.matcher(texto).matches()) {
            throw new RegraNegocioException(Categoria.NAO_ENCONTRADO, codigo, "Recurso não encontrado para esta conta.");
        }
        return UUID.fromString(texto);
    }

    static Integer limite(String texto) {
        if (texto == null) {
            return null;
        }
        if (!texto.matches("[0-9]{1,3}")) {
            throw new RegraNegocioException(Categoria.ENTRADA_INVALIDA, "LIMITE_PAGINA_INVALIDO",
                    "Limite deve estar entre 1 e 100.");
        }
        return Integer.valueOf(texto);
    }
}
