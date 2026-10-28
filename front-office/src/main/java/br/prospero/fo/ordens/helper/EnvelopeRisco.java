package br.prospero.fo.ordens.helper;

import br.prospero.fo.comum.dominio.MensagemInvalidaException;
import br.prospero.fo.comum.dominio.ParticaoBloqueadaException;
import br.prospero.fo.ordens.dominio.EstadoOrdem;
import br.prospero.fo.ordens.dominio.MotivoRejeicao;
import br.prospero.fo.ordens.dominio.Ordem;
import br.prospero.fo.ordens.dominio.ResultadoRisco;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Contrato dos eventos de risco (ADR-012): monta AVALIAR_RISCO e lê RESULTADO_RISCO de forma estrita (tamanho,
 * campos exatos, chave duplicada, tipos JSON, versão e origem). Valores monetários são texto decimal.
 */
public final class EnvelopeRisco {
    public static final String TOPICO_COMANDOS = "risco.comandos.v1";
    public static final String TOPICO_RESULTADOS = "risco.resultados.v1";
    public static final int TAMANHO_MAXIMO = 16 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> CAMPOS = Set.of("id", "tipo", "versao", "origem", "correlacao", "ocorridoEm",
            "conta", "traceparent", "dados");
    private static final Set<String> CAMPOS_RESULTADO = Set.of("resultado", "motivo", "valorReservado",
            "quantidadeReservada");
    private static final ObjectMapper JSON_ESTRITO = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> CAMPOS_RESULTADO_CANCELAMENTO = Set.of("ordem", "resultado", "motivo");
    private static final Set<String> MOTIVOS_CANCELAMENTO = Set.of("SEM_SALDO_VIVO", "ALVO_DIVERGENTE", "ORIGEM_OCO",
            "ADMISSAO_NAO_COMPROVADA", "RISCO_REJEITADO");
    private static final Pattern CONTA = Pattern.compile("CONTA-\\d{3}");
    private static final Pattern PRECO = Pattern.compile("\\d{1,13}\\.\\d{2}");
    private static final Pattern TRACEPARENT = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    private EnvelopeRisco() {
    }

    /** Comando para a intenção (ordem simples ou par OCO); precoBase é a base do túnel, também para Market. */
    public static String comando(UUID id, List<Ordem> ordens, BigDecimal precoBase, Instant agora, String traceparent) {
        Ordem primeira = ordens.getFirst();
        ObjectNode raiz = JSON.createObjectNode();
        raiz.put("id", id.toString());
        raiz.put("tipo", "AVALIAR_RISCO");
        raiz.put("versao", 2);
        raiz.put("origem", "front-office");
        raiz.put("correlacao", primeira.grupoEnvio().toString());
        raiz.put("ocorridoEm", agora.toString());
        raiz.put("conta", primeira.conta());
        raiz.put("traceparent", traceparent);
        ObjectNode dados = raiz.putObject("dados");
        dados.put("ticker", primeira.ticker());
        dados.put("lado", primeira.lado().name());
        dados.put("precoBase", texto(precoBase));
        dados.put("dataSessao", primeira.dataSessao().toString());
        ArrayNode pernas = dados.putArray("ordens");
        for (Ordem ordem : ordens) {
            ObjectNode perna = pernas.addObject();
            perna.put("id", ordem.id().toString());
            perna.put("tipo", ordem.tipo().name());
            perna.put("validade", ordem.validade().name());
            perna.put("quantidade", ordem.quantidade());
            perna.put("precoLimite", texto(ordem.precoLimite()));
            perna.put("precoGatilho", texto(ordem.precoGatilho()));
        }
        return raiz.toString();
    }

    public static ResultadoRisco resultado(byte[] bytes) {
        if (bytes == null || bytes.length > TAMANHO_MAXIMO) {
            throw new MensagemInvalidaException("tamanho");
        }
        JsonNode raiz;
        try {
            raiz = JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonProcessingException erro) {
            throw new MensagemInvalidaException("json");
        }
        if (raiz != null && raiz.path("tipo").isTextual() && "RESULTADO_CANCELAMENTO".equals(raiz.get("tipo").textValue())) {
            // Perder um resultado de cancelamento seria silencioso (retenção de 7 dias): bloquear, nunca descartar.
            throw new ParticaoBloqueadaException(resultadoCancelamentoValido(bytes)
                    ? "RESULTADO_CANCELAMENTO_SEM_LEITOR" : "RESULTADO_CANCELAMENTO_INVALIDO");
        }
        exigirCampos(raiz, CAMPOS, "envelope");
        if (!"RESULTADO_RISCO".equals(texto(raiz, "tipo")) || !raiz.get("versao").isInt()
                || raiz.get("versao").intValue() != 1) {
            throw new MensagemInvalidaException("tipo ou versao");
        }
        if (!"middle-office".equals(texto(raiz, "origem"))) {
            throw new MensagemInvalidaException("origem");
        }
        String conta = texto(raiz, "conta");
        if (!CONTA.matcher(conta).matches()) {
            throw new MensagemInvalidaException("conta");
        }
        try {
            Instant.parse(texto(raiz, "ocorridoEm"));
        } catch (RuntimeException erro) {
            throw new MensagemInvalidaException("ocorridoEm");
        }
        if (!raiz.get("traceparent").isNull() && !TRACEPARENT.matcher(texto(raiz, "traceparent")).matches()) {
            throw new MensagemInvalidaException("traceparent");
        }
        JsonNode dados = raiz.get("dados");
        exigirCampos(dados, CAMPOS_RESULTADO, "dados");
        EstadoOrdem estado = switch (texto(dados, "resultado")) {
            case "RESERVADA" -> EstadoOrdem.RESERVADA;
            case "REJEITADA" -> EstadoOrdem.REJEITADA;
            default -> throw new MensagemInvalidaException("resultado");
        };
        MotivoRejeicao motivo = null;
        if (!dados.get("motivo").isNull()) {
            try {
                motivo = MotivoRejeicao.valueOf(texto(dados, "motivo"));
            } catch (IllegalArgumentException erro) {
                throw new MensagemInvalidaException("motivo");
            }
        }
        exigirReservaCoerente(dados, estado);
        try {
            return new ResultadoRisco(uuid(raiz, "id"), uuid(raiz, "correlacao"), conta, estado, motivo,
                    Instant.parse(texto(raiz, "ocorridoEm")), raiz.get("traceparent").isNull() ? null : texto(raiz, "traceparent"),
                    EnvelopeMatching.assinatura(bytes));
        } catch (IllegalArgumentException erro) {
            throw new MensagemInvalidaException("motivo do resultado");
        }
    }

    /**
     * Contrato v1 do RESULTADO_CANCELAMENTO (ADR-013 RF10c2), lido de forma estrita só para escolher o motivo do
     * bloqueio: id derivado da ordem, correlação UUID canônica, resultado e motivo coerentes.
     */
    private static boolean resultadoCancelamentoValido(byte[] bytes) {
        try {
            JsonNode raiz = JSON_ESTRITO.readTree(new String(bytes, StandardCharsets.UTF_8));
            exigirCampos(raiz, CAMPOS, "envelope");
            JsonNode dados = raiz.get("dados");
            exigirCampos(dados, CAMPOS_RESULTADO_CANCELAMENTO, "dados");
            UUID ordem = uuid(dados, "ordem");
            String resultado = texto(dados, "resultado");
            JsonNode motivo = dados.get("motivo");
            boolean coerente = switch (resultado) {
                case "CONFIRMADO" -> motivo.isNull();
                case "RECUSADO" -> motivo.isTextual() && MOTIVOS_CANCELAMENTO.contains(motivo.textValue());
                default -> false;
            };
            uuid(raiz, "correlacao");
            Instant.parse(texto(raiz, "ocorridoEm"));
            return coerente && raiz.get("versao").isInt() && raiz.get("versao").intValue() == 1
                    && "middle-office".equals(texto(raiz, "origem"))
                    && CONTA.matcher(texto(raiz, "conta")).matches()
                    && (raiz.get("traceparent").isNull() || TRACEPARENT.matcher(texto(raiz, "traceparent")).matches())
                    && uuid(raiz, "id").equals(UUID.nameUUIDFromBytes(("resultado-cancelamento:" + ordem)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException erro) {
            return false;
        }
    }

    /** RESERVADA traz exatamente um recurso (valor em texto decimal ou quantidade inteira); REJEITADA, nenhum. */
    private static void exigirReservaCoerente(JsonNode dados, EstadoOrdem estado) {
        JsonNode valor = dados.get("valorReservado");
        JsonNode quantidade = dados.get("quantidadeReservada");
        boolean temValor = !valor.isNull();
        boolean temQuantidade = !quantidade.isNull();
        if (temValor && (!valor.isTextual() || !PRECO.matcher(valor.textValue()).matches()
                || new BigDecimal(valor.textValue()).signum() <= 0)) {
            throw new MensagemInvalidaException("valorReservado");
        }
        if (temQuantidade && (!quantidade.isIntegralNumber() || !quantidade.canConvertToLong()
                || quantidade.longValue() <= 0)) {
            throw new MensagemInvalidaException("quantidadeReservada");
        }
        boolean coerente = estado == EstadoOrdem.RESERVADA ? temValor != temQuantidade : !temValor && !temQuantidade;
        if (!coerente) {
            throw new MensagemInvalidaException("recurso reservado");
        }
    }

    private static void exigirCampos(JsonNode no, Set<String> campos, String onde) {
        if (no == null || !no.isObject() || no.size() != campos.size()) {
            throw new MensagemInvalidaException("campos de " + onde);
        }
        for (Iterator<String> nomes = no.fieldNames(); nomes.hasNext(); ) {
            if (!campos.contains(nomes.next())) {
                throw new MensagemInvalidaException("campos de " + onde);
            }
        }
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode valor = no.get(campo);
        if (valor == null || !valor.isTextual()) {
            throw new MensagemInvalidaException(campo);
        }
        return valor.textValue();
    }

    private static UUID uuid(JsonNode no, String campo) {
        String texto = texto(no, campo);
        try {
            UUID valor = UUID.fromString(texto);
            if (valor.toString().equals(texto)) {
                return valor;
            }
        } catch (IllegalArgumentException erro) {
            // tratado abaixo
        }
        throw new MensagemInvalidaException(campo);
    }

    private static String texto(BigDecimal valor) {
        return valor == null ? null : valor.setScale(2).toPlainString();
    }
}
