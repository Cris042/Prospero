package br.prospero.fo.ordens.rest;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.dominio.RegraNegocioException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** O pedido de cancelamento não carrega dados: corpo ausente ou exatamente o objeto JSON vazio. */
final class CorpoCancelamento {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private CorpoCancelamento() {
    }

    static void exigirVazio(String corpo, String tipoConteudo) {
        if (corpo == null || corpo.isBlank()) {
            return;
        }
        if (tipoConteudo == null
                || !"application/json".equals(tipoConteudo.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT))) {
            throw new RegraNegocioException(Categoria.ENTRADA_INVALIDA, "CORPO_CANCELAMENTO_INVALIDO",
                    "Corpo, quando enviado, deve ser application/json.");
        }
        try {
            JsonNode raiz = JSON.readTree(corpo);
            if (raiz != null && raiz.isObject() && raiz.isEmpty()) {
                return;
            }
        } catch (JsonProcessingException erro) {
            // cai na recusa abaixo, sem refletir o conteúdo
        }
        throw new RegraNegocioException(Categoria.ENTRADA_INVALIDA, "CORPO_CANCELAMENTO_INVALIDO",
                "O pedido de cancelamento não aceita dados: omita o corpo ou envie {}.");
    }
}
