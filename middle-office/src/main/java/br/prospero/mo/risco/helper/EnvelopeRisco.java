package br.prospero.mo.risco.helper;

import br.prospero.mo.mensageria.dominio.ParticaoBloqueadaException;
import br.prospero.mo.risco.dominio.ComandoRisco;
import br.prospero.mo.risco.dominio.Decisao;
import br.prospero.mo.risco.dominio.Lado;
import br.prospero.mo.risco.dominio.Perna;
import br.prospero.mo.risco.dominio.TipoOrdem;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Contrato dos eventos de risco (ADR-012). Leitura estrita: tamanho, campos exatos, chave duplicada, tipos JSON,
 * versão e origem do tópico; valores monetários são texto decimal. Nada fora do contrato chega ao domínio.
 */
public final class EnvelopeRisco {
    public static final String TOPICO_COMANDOS = "risco.comandos.v1";
    public static final String TOPICO_RESULTADOS = "risco.resultados.v1";
    public static final int TAMANHO_MAXIMO = 16 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final ObjectMapper JSON_ESTRITO = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> CAMPOS = Set.of("id", "tipo", "versao", "origem", "correlacao", "ocorridoEm",
            "conta", "traceparent", "dados");
    private static final Set<String> CAMPOS_COMANDO = Set.of("ticker", "lado", "precoBase", "ordens");
    private static final Set<String> CAMPOS_PERNA = Set.of("id", "tipo", "quantidade", "precoLimite", "precoGatilho");
    private static final Pattern CONTA = Pattern.compile("CONTA-\\d{3}");
    private static final Pattern TICKER = Pattern.compile("[A-Z0-9]{4,8}");
    private static final Pattern PRECO = Pattern.compile("\\d{1,7}(\\.\\d{1,2})?");
    private static final Pattern TRACEPARENT = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    private static final BigDecimal PRECO_MAXIMO = new BigDecimal("1000000.00");
    private static final long QUANTIDADE_MAXIMA = 1_000_000;

    private EnvelopeRisco() {
    }

    public static ComandoRisco comando(byte[] bytes) {
        JsonNode raiz = ler(bytes);
        if (raiz != null && raiz.path("tipo").isTextual() && "CANCELAMENTO_SOLICITADO".equals(raiz.get("tipo").textValue())) {
            // Perder um pedido de cancelamento seria silencioso (retenção de 7 dias): bloquear, nunca descartar.
            throw new ParticaoBloqueadaException(solicitacaoCancelamentoValida(bytes)
                    ? "CANCELAMENTO_SOLICITADO_SEM_COORDENACAO" : "CANCELAMENTO_SOLICITADO_INVALIDO");
        }
        exigirCampos(raiz, CAMPOS, "envelope");
        if (!"AVALIAR_RISCO".equals(texto(raiz, "tipo")) || !raiz.get("versao").isInt() || (raiz.get("versao").intValue() != 1 && raiz.get("versao").intValue() != 2)) {
            throw new EnvelopeInvalidoException("tipo ou versao");
        }
        if (!"front-office".equals(texto(raiz, "origem"))) {
            throw new EnvelopeInvalidoException("origem");
        }
        String conta = padrao(raiz, "conta", CONTA);
        String traceparent = raiz.get("traceparent").isNull() ? null : padrao(raiz, "traceparent", TRACEPARENT);
        instante(raiz, "ocorridoEm");
        JsonNode dados = raiz.get("dados");
        boolean v2 = raiz.get("versao").intValue() == 2;
        exigirCampos(dados, v2 ? Set.of("ticker", "lado", "precoBase", "ordens", "dataSessao") : CAMPOS_COMANDO, "dados");
        java.time.LocalDate sessao = v2 ? sessao(dados) : null;
        List<Perna> pernas = pernas(dados.get("ordens"), v2);
        if (v2) {
            exigirValidadesV2(pernas, dados);
        }
        if (pernas.size() == 2) {
            exigirParOco(pernas.get(0), pernas.get(1));
        }
        return new ComandoRisco(uuid(raiz, "id"), uuid(raiz, "correlacao"), conta, padrao(dados, "ticker", TICKER),
                enumeracao(dados, "lado", Lado.class), preco(dados, "precoBase"), pernas, traceparent, sessao);
    }

    /**
     * Contrato v1 do CANCELAMENTO_SOLICITADO (ADR-013 RF10c2), lido de forma estrita só para escolher o motivo do
     * bloqueio: id derivado da ordem, correlação = admissão alvo, campos exatos e sem conteúdo após o JSON.
     */
    private static boolean solicitacaoCancelamentoValida(byte[] bytes) {
        try {
            JsonNode raiz = JSON_ESTRITO.readTree(new String(bytes, StandardCharsets.UTF_8));
            exigirCampos(raiz, CAMPOS, "envelope");
            JsonNode dados = raiz.get("dados");
            exigirCampos(dados, Set.of("ordem", "intencao", "ticker", "dataSessao"), "dados");
            UUID ordem = uuid(dados, "ordem");
            UUID intencao = uuid(dados, "intencao");
            padrao(dados, "ticker", TICKER);
            sessao(dados);
            instante(raiz, "ocorridoEm");
            if (!raiz.get("traceparent").isNull()) padrao(raiz, "traceparent", TRACEPARENT);
            padrao(raiz, "conta", CONTA);
            return raiz.get("versao").isInt() && raiz.get("versao").intValue() == 1
                    && "front-office".equals(texto(raiz, "origem"))
                    && uuid(raiz, "id").equals(UUID.nameUUIDFromBytes(("cancelamento-solicitado:" + ordem).getBytes(StandardCharsets.UTF_8)))
                    && uuid(raiz, "correlacao").equals(UUID.nameUUIDFromBytes(("admissao:" + intencao).getBytes(StandardCharsets.UTF_8)));
        } catch (java.io.IOException | RuntimeException erro) {
            return false;
        }
    }

    private static JsonNode ler(byte[] bytes) {
        if (bytes == null || bytes.length > TAMANHO_MAXIMO) {
            throw new EnvelopeInvalidoException("tamanho");
        }
        try {
            return JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonProcessingException erro) {
            throw new EnvelopeInvalidoException("json");
        }
    }

    private static java.time.LocalDate sessao(JsonNode dados) {
        try {
            String data = texto(dados, "dataSessao");
            java.time.LocalDate sessao = java.time.LocalDate.parse(data);
            if (!sessao.toString().equals(data)) throw new IllegalArgumentException();
            return sessao;
        } catch (RuntimeException e) { throw new EnvelopeInvalidoException("dataSessao"); }
    }

    /** Uma ou duas pernas, cada uma com os campos da versão e os preços que o tipo exige. */
    private static List<Perna> pernas(JsonNode ordens, boolean v2) {
        if (!ordens.isArray() || ordens.isEmpty() || ordens.size() > 2) {
            throw new EnvelopeInvalidoException("ordens");
        }
        List<Perna> pernas = new ArrayList<>();
        for (JsonNode ordem : ordens) {
            exigirCampos(ordem, v2 ? Set.of("id", "tipo", "quantidade", "precoLimite", "precoGatilho", "validade") : CAMPOS_PERNA, "ordem");
            JsonNode quantidade = ordem.get("quantidade");
            if (!quantidade.canConvertToExactIntegral() || !quantidade.isIntegralNumber() || quantidade.longValue() < 1
                    || quantidade.longValue() > QUANTIDADE_MAXIMA) {
                throw new EnvelopeInvalidoException("quantidade");
            }
            Perna perna = new Perna(uuid(ordem, "id"), enumeracao(ordem, "tipo", TipoOrdem.class),
                    quantidade.longValue(), preco(ordem, "precoLimite"), preco(ordem, "precoGatilho"),
                    v2 ? enumeracao(ordem, "validade", br.prospero.mo.risco.dominio.Validade.class) : null);
            exigirPrecosDoTipo(perna);
            pernas.add(perna);
        }
        return pernas;
    }

    /** v2: Market nunca DIA, stops só DIA e Stop Limit com limite do lado certo do gatilho. */
    private static void exigirValidadesV2(List<Perna> pernas, JsonNode dados) {
        for (Perna perna : pernas) {
            boolean stop = perna.tipo().stop();
            if ((stop && perna.validade() != br.prospero.mo.risco.dominio.Validade.DIA)
                    || (perna.tipo() == TipoOrdem.MARKET && perna.validade() == br.prospero.mo.risco.dominio.Validade.DIA)) {
                throw new EnvelopeInvalidoException("validade");
            }
            if (perna.tipo() == TipoOrdem.STOP_LIMIT) {
                int comparacao = perna.precoLimite().compareTo(perna.precoGatilho());
                if (enumeracao(dados, "lado", Lado.class) == Lado.COMPRA ? comparacao < 0 : comparacao > 0) {
                    throw new EnvelopeInvalidoException("limite e gatilho");
                }
            }
        }
    }

    public static String resultado(UUID id, Decisao decisao, Instant agora, String traceparent) {
        ObjectNode raiz = JSON.createObjectNode();
        raiz.put("id", id.toString());
        raiz.put("tipo", "RESULTADO_RISCO");
        raiz.put("versao", 1);
        raiz.put("origem", "middle-office");
        raiz.put("correlacao", decisao.intencao().toString());
        raiz.put("ocorridoEm", agora.toString());
        raiz.put("conta", decisao.conta());
        raiz.put("traceparent", traceparent);
        ObjectNode dados = raiz.putObject("dados");
        dados.put("resultado", decisao.resultado().name());
        dados.put("motivo", decisao.motivo() == null ? null : decisao.motivo().name());
        dados.put("valorReservado", decisao.valorReservado() == null ? null : decisao.valorReservado().toPlainString());
        if (decisao.quantidadeReservada() == null) {
            dados.putNull("quantidadeReservada");
        } else {
            dados.put("quantidadeReservada", decisao.quantidadeReservada());
        }
        return raiz.toString();
    }

    /** OCO (ADR-010): Take Profit Limit + Stop Loss Stop/Stop Limit, ids distintos e mesma quantidade. */
    private static void exigirParOco(Perna alvo, Perna stop) {
        boolean stopValido = stop.tipo().stop();
        if (alvo.tipo() != TipoOrdem.LIMIT || !stopValido || alvo.id().equals(stop.id())
                || alvo.quantidade() != stop.quantidade()) {
            throw new EnvelopeInvalidoException("par OCO");
        }
    }

    /** Matriz da RF02 (ADR-010): cada tipo tem exatamente os preços que lhe cabem. */
    private static void exigirPrecosDoTipo(Perna perna) {
        boolean limite = perna.precoLimite() != null;
        boolean gatilho = perna.precoGatilho() != null;
        boolean coerente = switch (perna.tipo()) {
            case MARKET -> !limite && !gatilho;
            case LIMIT -> limite && !gatilho;
            case STOP -> !limite && gatilho;
            case STOP_LIMIT -> limite && gatilho;
        };
        if (!coerente) {
            throw new EnvelopeInvalidoException("precos do tipo");
        }
    }

    private static void exigirCampos(JsonNode no, Set<String> campos, String onde) {
        if (no == null || !no.isObject() || no.size() != campos.size()) {
            throw new EnvelopeInvalidoException("campos de " + onde);
        }
        for (Iterator<String> nomes = no.fieldNames(); nomes.hasNext(); ) {
            if (!campos.contains(nomes.next())) {
                throw new EnvelopeInvalidoException("campos de " + onde);
            }
        }
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode valor = no.get(campo);
        if (valor == null || !valor.isTextual()) {
            throw new EnvelopeInvalidoException(campo);
        }
        return valor.textValue();
    }

    private static String padrao(JsonNode no, String campo, Pattern padrao) {
        String valor = texto(no, campo);
        if (!padrao.matcher(valor).matches()) {
            throw new EnvelopeInvalidoException(campo);
        }
        return valor;
    }

    private static UUID uuid(JsonNode no, String campo) {
        try {
            UUID valor = UUID.fromString(texto(no, campo));
            if (!valor.toString().equals(texto(no, campo))) {
                throw new EnvelopeInvalidoException(campo);
            }
            return valor;
        } catch (IllegalArgumentException erro) {
            throw new EnvelopeInvalidoException(campo);
        }
    }

    private static void instante(JsonNode no, String campo) {
        try {
            Instant.parse(texto(no, campo));
        } catch (RuntimeException erro) {
            throw new EnvelopeInvalidoException(campo);
        }
    }

    private static <E extends Enum<E>> E enumeracao(JsonNode no, String campo, Class<E> tipo) {
        try {
            return Enum.valueOf(tipo, texto(no, campo));
        } catch (IllegalArgumentException erro) {
            throw new EnvelopeInvalidoException(campo);
        }
    }

    private static BigDecimal preco(JsonNode no, String campo) {
        if (no.get(campo).isNull()) {
            return null;
        }
        String valor = padrao(no, campo, PRECO);
        BigDecimal preco = new BigDecimal(valor).setScale(2);
        if (preco.signum() <= 0 || preco.compareTo(PRECO_MAXIMO) > 0) {
            throw new EnvelopeInvalidoException(campo);
        }
        return preco;
    }
}
