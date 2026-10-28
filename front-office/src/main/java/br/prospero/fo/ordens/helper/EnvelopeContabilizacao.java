package br.prospero.fo.ordens.helper;

import br.prospero.fo.comum.dominio.MensagemInvalidaException;
import br.prospero.fo.ordens.dominio.EventoContabilizacao;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Contrato BO independente do produtor, sem dependência entre serviços. */
public final class EnvelopeContabilizacao {
    public static final String TOPICO = "acompanhamento.eventos.v1";
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> ATIVOS = Set.of("ALFA3", "BETA3", "GAMA3", "DELTA3", "OMEGA3");
    private static final Set<String> CONTAS = Set.of("CONTA-001", "CONTA-002", "CONTA-003", "CONTA-004", "CONTA-005");
    private EnvelopeContabilizacao() { }

    public static EventoContabilizacao evento(byte[] bytes, String chave) {
        if (bytes == null || bytes.length == 0 || bytes.length > 16 * 1024) throw invalido();
        try {
            JsonNode raiz = JSON.readTree(bytes);
            campos(raiz, Set.of("id", "tipo", "versao", "origem", "causacao", "ocorridoEm", "traceparent", "dados"));
            if (!"NEGOCIO_CONTABILIZADO".equals(texto(raiz, "tipo"))
                    || !"back-office".equals(texto(raiz, "origem")) || !raiz.get("versao").isInt()
                    || raiz.get("versao").intValue() != 1) throw invalido();
            JsonNode dados = raiz.get("dados");
            campos(dados, Set.of("negocio", "ticker", "quantidade", "ordemCompra", "contaCompra", "ordemVenda", "contaVenda"));
            String ticker = texto(dados, "ticker"), negocio = texto(dados, "negocio");
            exigirNegocio(ticker, negocio, chave);
            UUID id = uuid(raiz, "id");
            if (!id.equals(UUID.nameUUIDFromBytes(("acompanhamento:" + negocio).getBytes(StandardCharsets.UTF_8)))) throw invalido();
            String quantidade = texto(dados, "quantidade");
            if (!quantidade.matches("[1-9][0-9]{0,18}")) throw invalido();
            long valor = Long.parseLong(quantidade);
            String compra = texto(dados, "contaCompra"), venda = texto(dados, "contaVenda");
            if (!CONTAS.contains(compra) || !CONTAS.contains(venda)) throw invalido();
            String trace = raiz.get("traceparent").isNull() ? null : texto(raiz, "traceparent");
            exigirTrace(trace);
            UUID ordemCompra = uuid(dados, "ordemCompra"), ordemVenda = uuid(dados, "ordemVenda");
            if (ordemCompra.equals(ordemVenda)) throw invalido();
            return new EventoContabilizacao(id, uuid(raiz, "causacao"), negocio, ticker, valor, ordemCompra, compra,
                    ordemVenda, venda, Instant.parse(texto(raiz, "ocorridoEm")), trace, EnvelopeMatching.assinatura(bytes));
        } catch (MensagemInvalidaException erro) {
            throw erro;
        } catch (java.io.IOException | IllegalArgumentException | java.time.DateTimeException erro) {
            throw invalido();
        }
    }

    private static void exigirNegocio(String ticker, String negocio, String chave) {
        if (!ATIVOS.contains(ticker) || !ticker.equals(chave) || negocio.length() > 64
                || !negocio.matches(ticker + "-[0-9]{4}-[0-9]{2}-[0-9]{2}-[1-9][0-9]{0,18}")) throw invalido();
    }

    private static void exigirTrace(String trace) {
        if (trace != null && (!trace.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                || trace.substring(3, 35).equals("0".repeat(32)) || trace.substring(36, 52).equals("0".repeat(16)))) throw invalido();
    }

    private static void campos(JsonNode no, Set<String> campos) {
        if (no == null || !no.isObject() || no.size() != campos.size()) throw invalido();
        var nomes = no.fieldNames();
        while (nomes.hasNext()) if (!campos.contains(nomes.next())) throw invalido();
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode valor = no.get(campo);
        if (valor == null || !valor.isTextual()) throw invalido();
        return valor.textValue();
    }

    private static UUID uuid(JsonNode no, String campo) {
        String texto = texto(no, campo);
        UUID id = UUID.fromString(texto);
        if (!id.toString().equals(texto)) throw invalido();
        return id;
    }

    private static MensagemInvalidaException invalido() {
        return new MensagemInvalidaException("CONTABILIZACAO_ENVELOPE_INVALIDO");
    }
}
