package br.prospero.me.matching.helper;

import br.prospero.me.livro.dominio.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Fronteira estrita. A origem declarada é contrato de interoperabilidade, sem autenticação no Kafka PLAINTEXT. */
public final class EnvelopeMatching {
    public static final String COMANDOS = "matching.comandos.v1";
    public static final String EVENTOS = "matching.eventos.v1";
    public static final int MAX_BYTES = 16 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> RAIZ = Set.of("id", "tipo", "versao", "origem", "correlacao", "ocorridoEm", "conta", "traceparent", "dados");
    private static final Set<String> DADOS = Set.of("ordem", "ticker", "lado", "tipo", "validade", "quantidade", "precoLimite", "precoMaximo", "dataSessao");
    private static final Set<String> ATIVOS = Set.of("ALFA3", "BETA3", "GAMA3", "DELTA3", "OMEGA3");

    private EnvelopeMatching() { }

    private static final Set<String> RAIZ_ENCERRAMENTO = Set.of("id", "tipo", "versao", "origem", "correlacao", "ocorridoEm", "traceparent", "dados");
    private static final Set<String> DADOS_ENCERRAMENTO = Set.of("ticker", "dataSessao");
    private static final Set<String> DADOS_OCO = Set.of("ticker", "lado", "dataSessao", "precoMaximo", "pernas");
    private static final Set<String> TAKE_PROFIT = Set.of("papel", "ordem", "tipo", "validade", "quantidade", "precoLimite");
    private static final Set<String> STOP_LOSS = Set.of("papel", "ordem", "tipo", "validade", "quantidade", "precoLimite", "precoGatilho");

    /** Encerramento de um ativo/data (RF05c.4); id = causação determinística dos eventos v4. */
    public record Encerramento(String ticker, LocalDate sessao) { }

    /**
     * Admissão simples, par OCO (v3) com admissao = Take Profit e stopLoss = Stop Loss, ou encerramento de sessão
     * (sem admissão).
     */
    public record Comando(UUID id, Instant ocorridoEm, String traceparent, Admissao admissao, Admissao stopLoss,
                          Encerramento encerramento) {
        public Comando(UUID id, Instant ocorridoEm, String traceparent, Admissao admissao) {
            this(id, ocorridoEm, traceparent, admissao, null, null);
        }

        public Comando(UUID id, Instant ocorridoEm, String traceparent, Admissao admissao, Admissao stopLoss) {
            this(id, ocorridoEm, traceparent, admissao, stopLoss, null);
        }

        public boolean oco() { return stopLoss != null; }

        public LocalDate sessao() { return encerramento != null ? encerramento.sessao() : admissao.sessao(); }

        /** Perna do comando com a ordem informada. */
        public Admissao perna(UUID ordem) {
            if (admissao.ordem().equals(ordem)) return admissao;
            if (oco() && stopLoss.ordem().equals(ordem)) return stopLoss;
            throw new IllegalArgumentException("Ordem fora do comando.");
        }
    }

    public static Comando comando(byte[] bytes, String chave) {
        try {
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalido();
            JsonNode raiz = JSON.readTree(bytes);
            if (raiz != null && raiz.path("tipo").isTextual() && raiz.get("tipo").textValue().equals("ENCERRAR_SESSAO")) {
                return encerramento(raiz, chave);
            }
            campos(raiz, RAIZ);
            int versao = raiz.path("versao").isInt() ? raiz.get("versao").intValue() : 0;
            if (!texto(raiz, "tipo").equals("ORDEM_ADMITIDA") || !raiz.get("versao").isInt()
                    || versao < 1 || versao > 3 || !texto(raiz, "origem").equals("middle-office")) throw invalido();
            UUID id = uuid(raiz, "id"), intencao = uuid(raiz, "correlacao");
            if (!id.equals(UUID.nameUUIDFromBytes(("admissao:" + intencao).getBytes(StandardCharsets.UTF_8)))) throw invalido();
            String conta = texto(raiz, "conta");
            if (!Set.of("CONTA-001", "CONTA-002", "CONTA-003", "CONTA-004").contains(conta)) throw invalido();
            String trace = raiz.get("traceparent").isNull() ? null : texto(raiz, "traceparent");
            if (trace != null && (!trace.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                    || trace.substring(3, 35).equals("0".repeat(32)) || trace.substring(36, 52).equals("0".repeat(16)))) throw invalido();
            Instant ocorrido = Instant.parse(texto(raiz, "ocorridoEm"));
            JsonNode dados = raiz.get("dados");
            if (versao == 3) return par(id, intencao, conta, ocorrido, trace, dados, chave);
            Set<String> camposDados = new HashSet<>(DADOS);
            if (versao == 2) camposDados.add("precoGatilho");
            campos(dados, camposDados);
            String ticker = texto(dados, "ticker");
            if (!ATIVOS.contains(ticker) || !ticker.equals(chave)) throw invalido();
            JsonNode q = dados.get("quantidade");
            if (!q.isIntegralNumber() || !q.canConvertToLong() || q.longValue() <= 0 || q.longValue() > 1_000_000) throw invalido();
            String data = texto(dados, "dataSessao");
            if (!data.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalido();
            String tipo = texto(dados, "tipo");
            boolean stop = Set.of("STOP", "STOP_LIMIT").contains(tipo);
            if (versao == 1 ? !Set.of("MARKET", "LIMIT").contains(tipo) : !stop) throw invalido();
            Admissao a = new Admissao(uuid(dados, "ordem"), intencao, conta, ticker, Lado.valueOf(texto(dados, "lado")),
                    TipoOrdem.valueOf(tipo), Validade.valueOf(texto(dados, "validade")), q.longValue(),
                    preco(dados, "precoLimite"), preco(dados, "precoMaximo"),
                    stop ? preco(dados, "precoGatilho") : null, LocalDate.parse(data));
            boolean compra = a.lado() == Lado.COMPRA, limit = a.tipo() == TipoOrdem.LIMIT || a.tipo() == TipoOrdem.STOP_LIMIT;
            if (limit != (a.precoLimite() != null) || compra != (a.precoMaximo() != null)
                    || (stop && (a.precoGatilho() == null || a.validade() != Validade.DIA))
                    || (!stop && !limit && a.validade() == Validade.DIA)
                    || (a.tipo() == TipoOrdem.STOP_LIMIT && (compra
                        ? a.precoLimite().compareTo(a.precoGatilho()) < 0
                        : a.precoLimite().compareTo(a.precoGatilho()) > 0))
                    || (compra && limit && a.precoLimite().compareTo(a.precoMaximo()) > 0)) throw invalido();
            return new Comando(id, ocorrido, trace, a);
        } catch (Exception erro) {
            // Não incluir conteúdo, IDs ou exceções de parser no diagnóstico.
            throw invalido();
        }
    }

    /** Par OCO v3 (RF05c.3b): duas pernas em ordem fixa, mesmas guardas do núcleo antes de qualquer mutação. */
    private static Comando par(UUID id, UUID intencao, String conta, Instant ocorrido, String trace, JsonNode dados,
                               String chave) {
        campos(dados, DADOS_OCO);
        String ticker = texto(dados, "ticker");
        if (!ATIVOS.contains(ticker) || !ticker.equals(chave)) throw invalido();
        String data = texto(dados, "dataSessao");
        if (!data.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalido();
        LocalDate sessao = LocalDate.parse(data);
        Lado lado = Lado.valueOf(texto(dados, "lado"));
        BigDecimal maximo = preco(dados, "precoMaximo");
        boolean compra = lado == Lado.COMPRA;
        if (compra != (maximo != null)) throw invalido();
        JsonNode pernas = dados.get("pernas");
        if (pernas == null || !pernas.isArray() || pernas.size() != 2) throw invalido();
        JsonNode tp = pernas.get(0), sl = pernas.get(1);
        campos(tp, TAKE_PROFIT);
        campos(sl, STOP_LOSS);
        if (!texto(tp, "papel").equals("TAKE_PROFIT") || !texto(sl, "papel").equals("STOP_LOSS")
                || !texto(tp, "tipo").equals("LIMIT") || !Set.of("STOP", "STOP_LIMIT").contains(texto(sl, "tipo"))
                || !texto(tp, "validade").equals("DIA") || !texto(sl, "validade").equals("DIA")) throw invalido();
        long q = quantidade(tp);
        if (q != quantidade(sl)) throw invalido();
        TipoOrdem tipoStop = TipoOrdem.valueOf(texto(sl, "tipo"));
        BigDecimal alvo = preco(tp, "precoLimite"), limiteStop = preco(sl, "precoLimite"), gatilho = preco(sl, "precoGatilho");
        UUID ordemTp = uuid(tp, "ordem"), ordemSl = uuid(sl, "ordem");
        if (alvo == null || gatilho == null || ordemTp.equals(ordemSl)
                || (tipoStop == TipoOrdem.STOP_LIMIT) != (limiteStop != null)
                || (compra ? alvo.compareTo(gatilho) >= 0 : alvo.compareTo(gatilho) <= 0)
                || (limiteStop != null && (compra ? limiteStop.compareTo(gatilho) < 0 : limiteStop.compareTo(gatilho) > 0))
                || (compra && (alvo.compareTo(maximo) > 0 || limiteStop != null && limiteStop.compareTo(maximo) > 0))) {
            throw invalido();
        }
        Admissao takeProfit = new Admissao(ordemTp, intencao, conta, ticker, lado, TipoOrdem.LIMIT, Validade.DIA, q,
                alvo, maximo, null, sessao);
        Admissao stopLoss = new Admissao(ordemSl, intencao, conta, ticker, lado, tipoStop, Validade.DIA, q,
                limiteStop, maximo, gatilho, sessao);
        return new Comando(id, ocorrido, trace, takeProfit, stopLoss);
    }

    /** Comando v1 do MO (RF05c.4): raiz sem conta, id = correlação = causação determinística do ativo/data. */
    private static Comando encerramento(JsonNode raiz, String chave) {
        campos(raiz, RAIZ_ENCERRAMENTO);
        if (!raiz.get("versao").isInt() || raiz.get("versao").intValue() != 1
                || !texto(raiz, "origem").equals("middle-office")) throw invalido();
        JsonNode dados = raiz.get("dados");
        campos(dados, DADOS_ENCERRAMENTO);
        String ticker = texto(dados, "ticker");
        String data = texto(dados, "dataSessao");
        if (!ATIVOS.contains(ticker) || !ticker.equals(chave) || !data.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalido();
        LocalDate sessao = LocalDate.parse(data);
        UUID id = uuid(raiz, "id");
        UUID esperado = UUID.nameUUIDFromBytes(("encerramento:" + ticker + ":" + sessao).getBytes(StandardCharsets.UTF_8));
        if (!id.equals(esperado) || !uuid(raiz, "correlacao").equals(esperado)) throw invalido();
        return new Comando(id, Instant.parse(texto(raiz, "ocorridoEm")), trace(raiz), null, null,
                new Encerramento(ticker, sessao));
    }

    private static String trace(JsonNode raiz) {
        String trace = raiz.get("traceparent").isNull() ? null : texto(raiz, "traceparent");
        if (trace != null && (!trace.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                || trace.substring(3, 35).equals("0".repeat(32)) || trace.substring(36, 52).equals("0".repeat(16)))) throw invalido();
        return trace;
    }

    /** Marco v4 do encerramento: correlação = causação = id do comando; id do evento derivado da causação. */
    public static byte[] marco(Comando comando, int indice, long expiradas) {
        ObjectNode raiz = JSON.createObjectNode();
        raiz.put("id", UUID.nameUUIDFromBytes(("matching:" + comando.id() + ":" + indice).getBytes(StandardCharsets.UTF_8)).toString());
        raiz.put("versao", 4);
        raiz.put("origem", "matching-engine");
        raiz.put("correlacao", comando.id().toString());
        raiz.put("ocorridoEm", comando.ocorridoEm().toString());
        raiz.put("ticker", comando.encerramento().ticker());
        raiz.put("dataSessao", comando.sessao().toString());
        raiz.put("traceparent", comando.traceparent());
        raiz.put("causacao", comando.id().toString());
        raiz.putObject("dados").put("expiradas", expiradas);
        raiz.put("tipo", "SESSAO_ENCERRADA");
        return raiz.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static long quantidade(JsonNode no) {
        JsonNode q = no.get("quantidade");
        if (!q.isIntegralNumber() || !q.canConvertToLong() || q.longValue() <= 0 || q.longValue() > 1_000_000) throw invalido();
        return q.longValue();
    }

    public static byte[] evento(Comando comando, Evento evento, int indice, Map<UUID, Comando> ordens) {
        int versao = versao(comando, evento, ordens);
        boolean v4 = versao == 4, v3 = versao == 3, v2 = versao == 2;
        UUID alvo = switch (evento) {
            case Evento.Negocio e -> e.agressor() == Lado.COMPRA ? e.ordemCompra() : e.ordemVenda();
            default -> envolvidos(evento).getFirst();
        };
        // Fatos OCO existem somente em v3; invariantes violadas indicam defeito do núcleo, não entrada externa.
        if (!v3 && (evento instanceof Evento.OrdemReduzida || (evento instanceof Evento.OrdemEncerrada fim
                && fim.motivo() == MotivoEncerramento.CANCELADA_OCO))) {
            throw new IllegalArgumentException("Evento OCO fora do contrato.");
        }
        if ((evento instanceof Evento.StopPendente || evento instanceof Evento.StopAcionado)
                && !stopPerna(ordens.get(alvo), alvo)) throw new IllegalArgumentException("Evento stop sem admissão stop.");
        ObjectNode raiz = JSON.createObjectNode();
        raiz.put("id", UUID.nameUUIDFromBytes(("matching:" + comando.id() + ":" + indice).getBytes(StandardCharsets.UTF_8)).toString());
        raiz.put("versao", versao);
        raiz.put("origem", "matching-engine");
        raiz.put("correlacao", (v2 || v3 || v4 ? ordens.get(alvo).id() : comando.id()).toString());
        raiz.put("ocorridoEm", comando.ocorridoEm().toString());
        raiz.put("ticker", evento.ticker());
        raiz.put("dataSessao", comando.sessao().toString());
        raiz.put("traceparent", comando.traceparent());
        if (v2 || v3 || v4) raiz.put("causacao", comando.id().toString());
        ObjectNode dados = raiz.putObject("dados");
        switch (evento) {
            case Evento.StopPendente e -> {
                raiz.put("tipo", "STOP_PENDENTE"); ordem(dados, e.ordem(), ordens);
                dados.put("precoGatilho", decimal(e.precoGatilho()));
            }
            case Evento.StopAcionado e -> {
                raiz.put("tipo", "STOP_ACIONADO"); ordem(dados, e.ordem(), ordens);
                dados.put("tipoExecutavel", e.tipoExecutavel().name()); dados.put("sequencia", e.sequencia());
            }
            case Evento.OrdemReduzida e -> {
                raiz.put("tipo", "ORDEM_REDUZIDA"); ordem(dados, e.ordem(), ordens);
                dados.put("reduzida", e.reduzida()); dados.put("restante", e.restante());
            }
            case Evento.OrdemAceita e -> {
                raiz.put("tipo", "ORDEM_ACEITA"); ordem(dados, e.ordem(), ordens); dados.put("sequencia", e.sequencia());
            }
            case Evento.OfertaNoLivro e -> {
                raiz.put("tipo", "OFERTA_NO_LIVRO"); ordem(dados, e.ordem(), ordens);
                dados.put("lado", e.lado().name()); dados.put("preco", decimal(e.preco())); dados.put("restante", e.restante());
            }
            case Evento.OrdemRejeitada e -> {
                raiz.put("tipo", "ORDEM_REJEITADA"); ordem(dados, e.ordem(), ordens); dados.put("motivo", e.motivo().name());
            }
            case Evento.OrdemEncerrada e -> {
                raiz.put("tipo", "ORDEM_ENCERRADA"); ordem(dados, e.ordem(), ordens);
                dados.put("motivo", e.motivo().name()); dados.put("executada", e.executada()); dados.put("cancelada", e.cancelada());
            }
            case Evento.Negocio e -> {
                raiz.put("tipo", "NEGOCIO"); dados.put("negocio", e.id()); dados.put("preco", decimal(e.preco()));
                dados.put("quantidade", e.quantidade()); dados.put("ordemCompra", e.ordemCompra().toString());
                dados.put("intencaoCompra", ordens.get(e.ordemCompra()).admissao().intencao().toString()); dados.put("contaCompra", e.contaCompra());
                dados.put("ordemVenda", e.ordemVenda().toString());
                dados.put("intencaoVenda", ordens.get(e.ordemVenda()).admissao().intencao().toString()); dados.put("contaVenda", e.contaVenda());
                dados.put("agressor", e.agressor().name());
            }
        }
        byte[] bytes = raiz.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalStateException("Evento ultrapassa orçamento.");
        return bytes;
    }

    /** v4 para o encerramento; senão v3 com OCO envolvido, v2 com stop, v1 no resto (ADR-013). */
    private static int versao(Comando comando, Evento evento, Map<UUID, Comando> ordens) {
        if (comando.encerramento() != null) {
            if (!(evento instanceof Evento.OrdemEncerrada fim) || fim.motivo() != MotivoEncerramento.EXPIRADA_DIA) {
                throw new IllegalArgumentException("Encerramento só publica expiração.");
            }
            return 4;
        }
        var alvos = envolvidos(evento);
        if (comando.oco() || alvos.stream().anyMatch(id -> ordens.get(id).oco())) return 3;
        return stop(comando) || alvos.stream().anyMatch(id -> stop(ordens.get(id))) ? 2 : 1;
    }

    private static boolean stop(Comando comando) {
        if (comando.admissao() == null) return false;
        return stopPerna(comando, comando.admissao().ordem());
    }

    private static boolean stopPerna(Comando comando, UUID ordem) {
        TipoOrdem tipo = comando.perna(ordem).tipo();
        return tipo == TipoOrdem.STOP || tipo == TipoOrdem.STOP_LIMIT;
    }

    private static java.util.List<UUID> envolvidos(Evento evento) {
        return switch (evento) {
            case Evento.Negocio e -> java.util.List.of(e.ordemCompra(), e.ordemVenda());
            case Evento.OrdemAceita e -> java.util.List.of(e.ordem());
            case Evento.OrdemRejeitada e -> java.util.List.of(e.ordem());
            case Evento.OfertaNoLivro e -> java.util.List.of(e.ordem());
            case Evento.OrdemEncerrada e -> java.util.List.of(e.ordem());
            case Evento.StopPendente e -> java.util.List.of(e.ordem());
            case Evento.StopAcionado e -> java.util.List.of(e.ordem());
            case Evento.OrdemReduzida e -> java.util.List.of(e.ordem());
        };
    }

    private static void ordem(ObjectNode dados, UUID id, Map<UUID, Comando> ordens) {
        Admissao a = ordens.get(id).admissao();
        dados.put("ordem", id.toString()); dados.put("intencao", a.intencao().toString()); dados.put("conta", a.conta());
    }

    private static String decimal(BigDecimal valor) { return valor.setScale(2).toPlainString(); }

    private static BigDecimal preco(JsonNode no, String campo) {
        if (no.get(campo).isNull()) return null;
        String valor = texto(no, campo);
        if (!valor.matches("(?:0|[1-9][0-9]{0,6})\\.[0-9]{2}")) throw invalido();
        BigDecimal preco = new BigDecimal(valor);
        if (preco.signum() <= 0 || preco.compareTo(new BigDecimal("1000000.00")) > 0) throw invalido();
        return preco;
    }

    private static UUID uuid(JsonNode no, String campo) {
        String valor = texto(no, campo); UUID id = UUID.fromString(valor);
        if (!id.toString().equals(valor)) throw invalido();
        return id;
    }

    private static String texto(JsonNode no, String campo) {
        if (!no.get(campo).isTextual()) throw invalido();
        return no.get(campo).textValue();
    }

    private static void campos(JsonNode no, Set<String> esperados) {
        if (no == null || !no.isObject()) throw invalido();
        Set<String> recebidos = new HashSet<>(); no.fieldNames().forEachRemaining(recebidos::add);
        if (!recebidos.equals(esperados)) throw invalido();
    }

    private static IllegalArgumentException invalido() { return new IllegalArgumentException("Envelope matching inválido."); }
}
