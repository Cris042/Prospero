package br.prospero.fo.ordens.helper;

import br.prospero.fo.comum.dominio.MensagemInvalidaException;
import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.dominio.LadoOrdem;
import br.prospero.fo.ordens.dominio.MotivoRejeicao;
import br.prospero.fo.ordens.dominio.TipoOrdem;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Contrato estrito independente do produtor (ADR-013); origem textual não autentica o canal PLAINTEXT. */
public final class EnvelopeMatching {
    public static final String TOPICO = "matching.eventos.v1";
    private static final Set<String> ATIVOS = Set.of("ALFA3", "BETA3", "GAMA3", "DELTA3", "OMEGA3");
    private static final Set<String> CONTAS = Set.of("CONTA-001", "CONTA-002", "CONTA-003", "CONTA-004");
    private static final Set<String> ENCERRAMENTOS = Set.of("EXECUTADA", "SALDO_CANCELADO_IOC", "FOK_SEM_LIQUIDEZ",
            "FORA_DA_FAIXA", "LIMITADA_PELA_RESERVA", "SEM_LIQUIDEZ", "LIMITE_NEGOCIOS");
    private static final Set<String> REJEICOES = Set.of("ATIVO_DESCONHECIDO", "COMBINACAO_INVALIDA",
            "PRECO_ACIMA_DA_RESERVA", "PRECO_INVALIDO", "QUANTIDADE_INVALIDA", "SESSAO_ANTERIOR", "SESSAO_ENCERRADA",
            "SESSAO_NAO_ENCERRADA", "LIMITE_ORDENS_ATIVAS");
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private EnvelopeMatching() {
    }

    public static EventoMatching evento(byte[] bytes, String chave) {
        JsonNode r = ler(bytes);
        int versao = r != null && r.path("versao").isInt() ? r.get("versao").intValue() : 0;
        // v2 (stops), v3 (OCO), v4 (encerramento) e v5 (cancelamento manual) acrescentam a causação raiz; v4 só expira e
        // marca a sessão; v5 só transporta o terminal CANCELADA_MANUAL e a decisão do cancelamento.
        boolean v2 = versao >= 2 && versao <= 5, v4 = versao == 4, v5 = versao == 5;
        if (v2) campos(r, "id", "tipo", "versao", "origem", "correlacao", "ocorridoEm", "ticker", "dataSessao", "traceparent", "dados", "causacao");
        else campos(r, "id", "tipo", "versao", "origem", "correlacao", "ocorridoEm", "ticker", "dataSessao", "traceparent", "dados");
        if (!r.get("versao").isInt() || (versao != 1 && !v2)
                || !"matching-engine".equals(texto(r, "origem"))) {
            throw invalido("versao ou origem");
        }
        String ticker = texto(r, "ticker");
        if (!ATIVOS.contains(ticker) || !ticker.equals(chave)) {
            throw invalido("ticker ou chave");
        }
        LocalDate sessao = sessao(r);
        exigirTraceparent(r);
        JsonNode d = r.get("dados");
        if (v4 != exclusivoDaV4(texto(r, "tipo"), d) || v5 != exclusivoDaV5(texto(r, "tipo"), d)) {
            throw invalido("versao");
        }
        EventoMatching.Detalhe detalhe = detalhe(texto(r, "tipo"), d, ticker, sessao, versao);
        if (v5) exigirDerivacaoV5(uuid(r, "id"), uuid(r, "correlacao"), uuid(r, "causacao"), detalhe);
        return new EventoMatching(uuid(r, "id"), uuid(r, "correlacao"), ticker, sessao, assinatura(bytes), detalhe,
                new EventoMatching.Contexto(v2 ? uuid(r, "causacao") : null, versao, Instant.parse(texto(r, "ocorridoEm")),
                        r.get("traceparent").isNull() ? null : texto(r, "traceparent")));
    }

    private static JsonNode ler(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 16 * 1024) {
            throw invalido("tamanho");
        }
        try {
            return JSON.readTree(bytes);
        } catch (IOException erro) {
            throw invalido("json");
        }
    }

    private static LocalDate sessao(JsonNode r) {
        try {
            Instant.parse(texto(r, "ocorridoEm"));
            String data = texto(r, "dataSessao");
            LocalDate sessao = LocalDate.parse(data);
            if (!sessao.toString().equals(data)) {
                throw invalido("dataSessao");
            }
            return sessao;
        } catch (java.time.DateTimeException erro) {
            throw invalido("data");
        }
    }

    private static void exigirTraceparent(JsonNode r) {
        JsonNode trace = r.get("traceparent");
        if (!trace.isNull() && (!trace.isTextual()
                || !trace.textValue().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"))) {
            throw invalido("traceparent");
        }
    }

    /** v4 transporta somente o marco e a expiração DIA. */
    private static boolean exclusivoDaV4(String tipoEvento, JsonNode d) {
        return "SESSAO_ENCERRADA".equals(tipoEvento) || "ORDEM_ENCERRADA".equals(tipoEvento) && d != null
                && d.path("motivo").isTextual() && "EXPIRADA_DIA".equals(d.path("motivo").textValue());
    }

    /** v5 transporta somente o terminal CANCELADA_MANUAL e a decisão do cancelamento. */
    private static boolean exclusivoDaV5(String tipoEvento, JsonNode d) {
        return "CANCELAMENTO_DECIDIDO".equals(tipoEvento) || "ORDEM_ENCERRADA".equals(tipoEvento) && d != null
                && d.path("motivo").isTextual() && "CANCELADA_MANUAL".equals(d.path("motivo").textValue());
    }

    /**
     * Identidade derivada do comando de cancelamento: causação, correlação (admissão alvo) e ids dos eventos
     * ({@code matching:<comando>:<índice>}, terminal no índice 0 e decisão por último).
     */
    private static void exigirDerivacaoV5(UUID id, UUID correlacao, UUID causacao, EventoMatching.Detalhe detalhe) {
        var alvo = switch (detalhe) {
            case EventoMatching.Encerrada f -> f.alvo();
            case EventoMatching.CancelamentoDecidido d -> d.alvo();
            default -> throw invalido("versao");
        };
        boolean aplicado = detalhe instanceof EventoMatching.CancelamentoDecidido d
                && d.resultado() == EventoMatching.ResultadoCancelamento.APLICADO;
        UUID terminal = derivado(causacao, 0);
        if (!causacao.equals(EventoMatching.comandoCancelamento(alvo.ordem())) || !correlacao.equals(admissao(alvo))
                || !id.equals(derivado(causacao, aplicado ? 1 : 0))) {
            throw invalido("identidade do cancelamento");
        }
        if (detalhe instanceof EventoMatching.CancelamentoDecidido d && !java.util.Objects.equals(d.terminal(), aplicado ? terminal : null)) {
            throw invalido("terminal");
        }
    }

    private static UUID derivado(UUID comando, int indice) {
        return UUID.nameUUIDFromBytes(("matching:" + comando + ":" + indice).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID admissao(EventoMatching.Alvo alvo) {
        return UUID.nameUUIDFromBytes(("admissao:" + alvo.intencao()).getBytes(StandardCharsets.UTF_8));
    }

    private static EventoMatching.CancelamentoDecidido decidido(JsonNode d, boolean v5) {
        if (!v5) throw invalido("versao");
        campos(d, "ordem", "intencao", "conta", "resultado", "terminal");
        EventoMatching.ResultadoCancelamento resultado;
        try {
            resultado = EventoMatching.ResultadoCancelamento.valueOf(texto(d, "resultado"));
        } catch (IllegalArgumentException erro) {
            throw invalido("resultado");
        }
        UUID terminal = d.get("terminal").isNull() ? null : uuid(d, "terminal");
        if ((resultado == EventoMatching.ResultadoCancelamento.APLICADO) != (terminal != null)) {
            throw invalido("terminal");
        }
        return new EventoMatching.CancelamentoDecidido(alvo(d, ""), resultado, terminal);
    }

    private static EventoMatching.Detalhe detalhe(String tipoEvento, JsonNode d, String ticker, LocalDate sessao,
                                                  int versao) {
        boolean v2 = versao >= 2 && versao <= 5, v3 = versao == 3, v4 = versao == 4, v5 = versao == 5;
        return switch (tipoEvento) {
            case "ORDEM_ACEITA" -> {
                campos(d, "ordem", "intencao", "conta", "sequencia");
                yield new EventoMatching.Aceita(alvo(d, ""), inteiro(d, "sequencia", 1, Long.MAX_VALUE));
            }
            case "STOP_PENDENTE" -> {
                if (!v2) throw invalido("versao");
                campos(d, "ordem", "intencao", "conta", "precoGatilho");
                yield new EventoMatching.StopPendente(alvo(d, ""), preco(d, "precoGatilho"));
            }
            case "STOP_ACIONADO" -> stopAcionado(d, v2);
            case "SESSAO_ENCERRADA" -> {
                campos(d, "expiradas");
                yield new EventoMatching.SessaoEncerrada(inteiro(d, "expiradas", 0, 1_000_000));
            }
            case "ORDEM_REDUZIDA" -> {
                if (!v3) throw invalido("versao");
                campos(d, "ordem", "intencao", "conta", "reduzida", "restante");
                yield new EventoMatching.Reduzida(alvo(d, ""), inteiro(d, "reduzida", 1, 1_000_000),
                        inteiro(d, "restante", 1, 1_000_000));
            }
            case "OFERTA_NO_LIVRO" -> {
                campos(d, "ordem", "intencao", "conta", "lado", "preco", "restante");
                yield new EventoMatching.Oferta(alvo(d, ""), lado(d, "lado"), preco(d), inteiro(d, "restante", 1, 1_000_000));
            }
            case "ORDEM_REJEITADA" -> rejeitada(d, v2);
            case "ORDEM_ENCERRADA" -> encerrada(d, v2, v3, v4, v5);
            case "CANCELAMENTO_DECIDIDO" -> decidido(d, v5);
            case "NEGOCIO" -> negocio(d, ticker, sessao);
            default -> throw invalido("tipo");
        };
    }

    private static EventoMatching.StopAcionado stopAcionado(JsonNode d, boolean v2) {
        if (!v2) throw invalido("versao");
        campos(d, "ordem", "intencao", "conta", "tipoExecutavel", "sequencia");
        String tipo = texto(d, "tipoExecutavel");
        if (!Set.of("MARKET", "LIMIT").contains(tipo)) throw invalido("tipoExecutavel");
        return new EventoMatching.StopAcionado(alvo(d, ""), TipoOrdem.valueOf(tipo), inteiro(d, "sequencia", 1, Long.MAX_VALUE));
    }

    private static EventoMatching.Rejeitada rejeitada(JsonNode d, boolean v2) {
        campos(d, "ordem", "intencao", "conta", "motivo");
        String motivo = texto(d, "motivo");
        if (!REJEICOES.contains(motivo) && !(v2 && "LIMITE_STOPS_PENDENTES".equals(motivo))) {
            throw invalido("motivo");
        }
        return new EventoMatching.Rejeitada(alvo(d, ""), MotivoRejeicao.valueOf(motivo));
    }

    /** v2 habilita LIMITE_CASCATA, v3 CANCELADA_OCO, v4 EXPIRADA_DIA e v5 CANCELADA_MANUAL (saldo retirado positivo). */
    private static EventoMatching.Encerrada encerrada(JsonNode d, boolean v2, boolean v3, boolean v4, boolean v5) {
        campos(d, "ordem", "intencao", "conta", "motivo", "executada", "cancelada");
        String motivo = texto(d, "motivo");
        if (!ENCERRAMENTOS.contains(motivo) && !(v2 && "LIMITE_CASCATA".equals(motivo))
                && !(v3 && "CANCELADA_OCO".equals(motivo)) && !(v4 && "EXPIRADA_DIA".equals(motivo))
                && !(v5 && "CANCELADA_MANUAL".equals(motivo))) {
            throw invalido("motivo");
        }
        long executada = inteiro(d, "executada", 0, 1_000_000);
        long cancelada = inteiro(d, "cancelada", v5 ? 1 : 0, 1_000_000);
        if (v5 && executada + cancelada > 1_000_000) throw invalido("cancelada");
        return new EventoMatching.Encerrada(alvo(d, ""), motivo, executada, cancelada);
    }

    private static EventoMatching.Negocio negocio(JsonNode d, String ticker, LocalDate sessao) {
        campos(d, "negocio", "preco", "quantidade", "ordemCompra", "intencaoCompra", "contaCompra",
                "ordemVenda", "intencaoVenda", "contaVenda", "agressor");
        String negocio = texto(d, "negocio");
        if (!negocio.matches(java.util.regex.Pattern.quote(ticker + "-" + sessao + "-") + "[1-9][0-9]{0,9}")) {
            throw invalido("negocio");
        }
        var compra = alvo(d, "Compra");
        var venda = alvo(d, "Venda");
        if (compra.ordem().equals(venda.ordem()) || compra.intencao().equals(venda.intencao())) {
            throw invalido("lados do negocio");
        }
        return new EventoMatching.Negocio(negocio, preco(d), inteiro(d, "quantidade", 1, 1_000_000),
                compra, venda, lado(d, "agressor"));
    }

    public static String assinatura(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException erro) {
            throw new IllegalStateException("SHA-256 indisponível", erro);
        }
    }

    /** Assinatura de negócio independente do ID do envelope e do produtor/replay. */
    public static String assinaturaNegocio(EventoMatching e, EventoMatching.Negocio n) {
        String valor = e.ticker() + "|" + e.sessao() + "|" + n.id() + "|" + n.preco().toPlainString()
                + "|" + n.quantidade() + "|" + n.compra() + "|" + n.venda() + "|" + n.agressor();
        return assinatura(valor.getBytes(StandardCharsets.UTF_8));
    }

    private static EventoMatching.Alvo alvo(JsonNode d, String sufixo) {
        String conta = texto(d, "conta" + sufixo);
        if (!CONTAS.contains(conta)) {
            throw invalido("conta");
        }
        return new EventoMatching.Alvo(uuid(d, "ordem" + sufixo), uuid(d, "intencao" + sufixo), conta);
    }

    private static void campos(JsonNode n, String... nomes) {
        if (n == null || !n.isObject() || n.size() != nomes.length) {
            throw invalido("campos");
        }
        for (String nome : nomes) {
            if (!n.has(nome)) {
                throw invalido("campos");
            }
        }
    }

    private static String texto(JsonNode n, String nome) {
        JsonNode v = n.get(nome);
        if (v == null || !v.isTextual()) {
            throw invalido(nome);
        }
        return v.textValue();
    }

    private static UUID uuid(JsonNode n, String nome) {
        String v = texto(n, nome);
        try {
            UUID id = UUID.fromString(v);
            if (id.toString().equals(v)) {
                return id;
            }
        } catch (IllegalArgumentException erro) {
            // Tratado abaixo sem incluir conteúdo externo na mensagem.
        }
        throw invalido(nome);
    }

    private static long inteiro(JsonNode n, String nome, long minimo, long maximo) {
        JsonNode v = n.get(nome);
        if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < minimo || v.longValue() > maximo) {
            throw invalido(nome);
        }
        return v.longValue();
    }

    private static BigDecimal preco(JsonNode n) { return preco(n, "preco"); }

    private static BigDecimal preco(JsonNode n, String campo) {
        String valor = texto(n, campo);
        if (!valor.matches("[0-9]{1,7}\\.[0-9]{2}")) {
            throw invalido("preco");
        }
        BigDecimal preco = new BigDecimal(valor);
        if (preco.signum() <= 0 || preco.compareTo(new BigDecimal("1000000.00")) > 0) {
            throw invalido("preco");
        }
        return preco;
    }

    private static LadoOrdem lado(JsonNode n, String campo) {
        try {
            return LadoOrdem.valueOf(texto(n, campo));
        } catch (IllegalArgumentException erro) {
            throw invalido(campo);
        }
    }

    private static MensagemInvalidaException invalido(String motivo) {
        return new MensagemInvalidaException("matching: " + motivo);
    }
}
