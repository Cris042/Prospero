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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    private static final Set<String> DADOS_STOP = Set.of("ordem", "ticker", "lado", "tipo", "validade", "quantidade", "precoLimite", "precoMaximo", "precoGatilho", "dataSessao");
    private static final Set<String> ATIVOS = Set.of("ALFA3", "BETA3", "GAMA3", "DELTA3", "OMEGA3");
    private static final Set<String> CONTAS = Set.of("CONTA-001", "CONTA-002", "CONTA-003", "CONTA-004");
    private static final String DATA = "[0-9]{4}-[0-9]{2}-[0-9]{2}";
    private static final String TRACEPARENT = "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}";
    private static final int QUANTIDADE_MAXIMA = 1_000_000;

    private EnvelopeMatching() { }

    private static final Set<String> RAIZ_ENCERRAMENTO = Set.of("id", "tipo", "versao", "origem", "correlacao", "ocorridoEm", "traceparent", "dados");
    private static final Set<String> DADOS_ENCERRAMENTO = Set.of("ticker", "dataSessao");
    private static final Set<String> DADOS_CANCELAMENTO = Set.of("ordem", "intencao", "conta", "ticker", "dataSessao");
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
                          Encerramento encerramento, AlvoCancelamento cancelamento) {
        public Comando(UUID id, Instant ocorridoEm, String traceparent, Admissao admissao) {
            this(id, ocorridoEm, traceparent, admissao, null, null, null);
        }

        public Comando(UUID id, Instant ocorridoEm, String traceparent, Admissao admissao, Admissao stopLoss) {
            this(id, ocorridoEm, traceparent, admissao, stopLoss, null, null);
        }

        public Comando(UUID id, Instant ocorridoEm, String traceparent, Admissao admissao, Admissao stopLoss,
                       Encerramento encerramento) {
            this(id, ocorridoEm, traceparent, admissao, stopLoss, encerramento, null);
        }

        public boolean oco() { return stopLoss != null; }

        public LocalDate sessao() {
            if (cancelamento != null) return cancelamento.sessao();
            return encerramento != null ? encerramento.sessao() : admissao.sessao();
        }

        /** Perna do comando com a ordem informada. */
        public Admissao perna(UUID ordem) {
            if (admissao.ordem().equals(ordem)) return admissao;
            if (oco() && stopLoss.ordem().equals(ordem)) return stopLoss;
            throw new IllegalArgumentException("Ordem fora do comando.");
        }
    }

    /** Raiz já validada de uma admissão v1–v3: id determinístico pela intenção, conta conhecida e traceparent W3C. */
    private record Cabecalho(UUID id, UUID intencao, String conta, Instant ocorridoEm, String traceparent, int versao) { }

    public static Comando comando(byte[] bytes, String chave) {
        try {
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalido();
            JsonNode raiz = JSON.readTree(bytes);
            if (encerramentoDeSessao(raiz)) return encerramento(raiz, chave);
            if (cancelarOrdem(raiz)) return cancelamento(raiz, chave);
            Cabecalho cabecalho = cabecalho(raiz);
            JsonNode dados = raiz.get("dados");
            if (cabecalho.versao() == 3) return par(cabecalho, dados, chave);
            return simples(cabecalho, dados, chave);
        } catch (Exception erro) {
            // Não incluir conteúdo, IDs ou exceções de parser no diagnóstico.
            throw invalido();
        }
    }

    private static boolean encerramentoDeSessao(JsonNode raiz) {
        return raiz != null && raiz.path("tipo").isTextual() && raiz.get("tipo").textValue().equals("ENCERRAR_SESSAO");
    }

    private static boolean cancelarOrdem(JsonNode raiz) {
        return raiz != null && raiz.path("tipo").isTextual() && raiz.get("tipo").textValue().equals("CANCELAR_ORDEM");
    }

    /**
     * Comando v1 do MO (RF10c2): raiz sem conta, id = uuid("cancelamento:" + ordem) e correlação = admissão alvo; a
     * conta e o ativo (igual à chave Kafka) vivem em dados, como a identidade completa do alvo.
     */
    private static Comando cancelamento(JsonNode raiz, String chave) {
        campos(raiz, RAIZ_ENCERRAMENTO);
        if (!raiz.get("versao").isInt() || raiz.get("versao").intValue() != 1 || !daOrigemMiddleOffice(raiz)) {
            throw invalido();
        }
        JsonNode dados = raiz.get("dados");
        campos(dados, DADOS_CANCELAMENTO);
        String ticker = ticker(dados, chave);
        String conta = texto(dados, "conta");
        if (!CONTAS.contains(conta)) throw invalido();
        UUID ordem = uuid(dados, "ordem");
        UUID intencao = uuid(dados, "intencao");
        LocalDate sessao = sessao(dados);
        UUID id = uuid(raiz, "id");
        if (!id.equals(uuidDeterministico("cancelamento:" + ordem))
                || !uuid(raiz, "correlacao").equals(uuidDeterministico("admissao:" + intencao))) {
            throw invalido();
        }
        return new Comando(id, Instant.parse(texto(raiz, "ocorridoEm")), trace(raiz), null, null, null,
                new AlvoCancelamento(ordem, intencao, conta, ticker, sessao));
    }

    /**
     * Eventos v5 de um CANCELAR_ORDEM (ADR-013 RF10c2): terminal {@code CANCELADA_MANUAL} só quando aplicado (índice 0)
     * e a decisão como último evento. O fato interno {@link SaldoCanceladoManual} não entra no catálogo {@link Evento}.
     */
    public static List<byte[]> cancelamento(Comando comando, ResultadoCancelamentoManual resultado) {
        AlvoCancelamento alvo = comando.cancelamento();
        UUID admissao = uuidDeterministico("admissao:" + alvo.intencao());
        List<byte[]> saida = new ArrayList<>(2);
        String terminal = null;
        if (resultado.situacao() == ResultadoCancelamentoManual.Situacao.APLICADO) {
            SaldoCanceladoManual saldo = resultado.terminais().getFirst();
            if (!saldo.ordem().equals(alvo.ordem()) || !saldo.ticker().equals(alvo.ticker())) {
                throw new IllegalStateException("Terminal divergente do alvo.");
            }
            ObjectNode raiz = cabecalhoEvento(comando, 0, 5, admissao, alvo.ticker());
            ObjectNode dados = raiz.putObject("dados");
            identidade(dados, alvo);
            dados.put("motivo", "CANCELADA_MANUAL");
            dados.put("executada", saldo.executada());
            dados.put("cancelada", saldo.cancelada());
            raiz.put("tipo", "ORDEM_ENCERRADA");
            terminal = raiz.get("id").textValue();
            saida.add(serializar(raiz));
        }
        ObjectNode raiz = cabecalhoEvento(comando, saida.size(), 5, admissao, alvo.ticker());
        ObjectNode dados = raiz.putObject("dados");
        identidade(dados, alvo);
        dados.put("resultado", resultado.situacao().name());
        dados.put("terminal", terminal);
        raiz.put("tipo", "CANCELAMENTO_DECIDIDO");
        saida.add(serializar(raiz));
        return List.copyOf(saida);
    }

    private static void identidade(ObjectNode dados, AlvoCancelamento alvo) {
        dados.put("ordem", alvo.ordem().toString());
        dados.put("intencao", alvo.intencao().toString());
        dados.put("conta", alvo.conta());
    }

    private static byte[] serializar(ObjectNode raiz) {
        byte[] bytes = raiz.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalStateException("Evento ultrapassa orçamento.");
        return bytes;
    }

    private static Cabecalho cabecalho(JsonNode raiz) {
        campos(raiz, RAIZ);
        JsonNode versao = raiz.get("versao");
        if (!texto(raiz, "tipo").equals("ORDEM_ADMITIDA") || !versao.isInt() || versao.intValue() < 1
                || versao.intValue() > 3 || !daOrigemMiddleOffice(raiz)) throw invalido();
        UUID id = uuid(raiz, "id");
        UUID intencao = uuid(raiz, "correlacao");
        if (!id.equals(uuidDeterministico("admissao:" + intencao))) throw invalido();
        String conta = texto(raiz, "conta");
        if (!CONTAS.contains(conta)) throw invalido();
        String traceparent = trace(raiz);
        return new Cabecalho(id, intencao, conta, Instant.parse(texto(raiz, "ocorridoEm")), traceparent,
                versao.intValue());
    }

    /** Admissão simples: v1 só Market/Limit; v2 só Stop/Stop Limit, com gatilho. */
    private static Comando simples(Cabecalho cabecalho, JsonNode dados, String chave) {
        boolean contratoStop = cabecalho.versao() == 2;
        campos(dados, contratoStop ? DADOS_STOP : DADOS);
        String ticker = ticker(dados, chave);
        long quantidade = quantidade(dados);
        LocalDate sessao = sessao(dados);
        TipoOrdem tipo = TipoOrdem.valueOf(texto(dados, "tipo"));
        if (tipo.stop() != contratoStop) throw invalido();
        Admissao admissao = new Admissao(uuid(dados, "ordem"), cabecalho.intencao(), cabecalho.conta(), ticker,
                Lado.valueOf(texto(dados, "lado")), tipo, Validade.valueOf(texto(dados, "validade")), quantidade,
                preco(dados, "precoLimite"), preco(dados, "precoMaximo"),
                contratoStop ? preco(dados, "precoGatilho") : null, sessao);
        if (!admissivel(admissao)) throw invalido();
        return new Comando(cabecalho.id(), cabecalho.ocorridoEm(), cabecalho.traceparent(), admissao);
    }

    /** Par OCO v3 (RF05c.3b): duas pernas em ordem fixa, mesmas guardas do núcleo antes de qualquer mutação. */
    private static Comando par(Cabecalho cabecalho, JsonNode dados, String chave) {
        campos(dados, DADOS_OCO);
        String ticker = ticker(dados, chave);
        LocalDate sessao = sessao(dados);
        Lado lado = Lado.valueOf(texto(dados, "lado"));
        BigDecimal maximo = preco(dados, "precoMaximo");
        JsonNode pernas = dados.get("pernas");
        if (pernas == null || !pernas.isArray() || pernas.size() != 2) throw invalido();
        JsonNode dadosTakeProfit = pernas.get(0);
        JsonNode dadosStopLoss = pernas.get(1);
        campos(dadosTakeProfit, TAKE_PROFIT);
        campos(dadosStopLoss, STOP_LOSS);
        TipoOrdem tipoStop = TipoOrdem.valueOf(texto(dadosStopLoss, "tipo"));
        if (!texto(dadosTakeProfit, "papel").equals("TAKE_PROFIT") || !texto(dadosStopLoss, "papel").equals("STOP_LOSS")
                || !texto(dadosTakeProfit, "tipo").equals("LIMIT") || !tipoStop.stop()
                || !texto(dadosTakeProfit, "validade").equals("DIA") || !texto(dadosStopLoss, "validade").equals("DIA")) {
            throw invalido();
        }
        long quantidade = quantidade(dadosTakeProfit);
        if (quantidade != quantidade(dadosStopLoss)) throw invalido();
        Admissao takeProfit = new Admissao(uuid(dadosTakeProfit, "ordem"), cabecalho.intencao(), cabecalho.conta(),
                ticker, lado, TipoOrdem.LIMIT, Validade.DIA, quantidade, preco(dadosTakeProfit, "precoLimite"), maximo,
                null, sessao);
        Admissao stopLoss = new Admissao(uuid(dadosStopLoss, "ordem"), cabecalho.intencao(), cabecalho.conta(),
                ticker, lado, tipoStop, Validade.DIA, quantidade, preco(dadosStopLoss, "precoLimite"), maximo,
                preco(dadosStopLoss, "precoGatilho"), sessao);
        // Mesmas guardas do núcleo (Admissao): o par admitido nunca é recusado por combinação no MotorMatching.
        if (takeProfit.ordem().equals(stopLoss.ordem()) || !admissivel(takeProfit) || !admissivel(stopLoss)
                || !takeProfit.alvoAlemDoGatilho(stopLoss)) {
            throw invalido();
        }
        return new Comando(cabecalho.id(), cabecalho.ocorridoEm(), cabecalho.traceparent(), takeProfit, stopLoss);
    }

    /** Comando v1 do MO (RF05c.4): raiz sem conta, id = correlação = causação determinística do ativo/data. */
    private static Comando encerramento(JsonNode raiz, String chave) {
        campos(raiz, RAIZ_ENCERRAMENTO);
        if (!raiz.get("versao").isInt() || raiz.get("versao").intValue() != 1 || !daOrigemMiddleOffice(raiz)) {
            throw invalido();
        }
        JsonNode dados = raiz.get("dados");
        campos(dados, DADOS_ENCERRAMENTO);
        String ticker = ticker(dados, chave);
        LocalDate sessao = sessao(dados);
        UUID id = uuid(raiz, "id");
        UUID esperado = uuidDeterministico("encerramento:" + ticker + ":" + sessao);
        if (!id.equals(esperado) || !uuid(raiz, "correlacao").equals(esperado)) throw invalido();
        return new Comando(id, Instant.parse(texto(raiz, "ocorridoEm")), trace(raiz), null, null,
                new Encerramento(ticker, sessao));
    }

    private static boolean daOrigemMiddleOffice(JsonNode raiz) {
        return texto(raiz, "origem").equals("middle-office");
    }

    private static String trace(JsonNode raiz) {
        String trace = raiz.get("traceparent").isNull() ? null : texto(raiz, "traceparent");
        if (trace != null && (!trace.matches(TRACEPARENT)
                || trace.substring(3, 35).equals("0".repeat(32)) || trace.substring(36, 52).equals("0".repeat(16)))) throw invalido();
        return trace;
    }

    /** Ativo conhecido e igual à chave Kafka. */
    private static String ticker(JsonNode dados, String chave) {
        String ticker = texto(dados, "ticker");
        if (!ATIVOS.contains(ticker) || !ticker.equals(chave)) throw invalido();
        return ticker;
    }

    private static LocalDate sessao(JsonNode dados) {
        String data = texto(dados, "dataSessao");
        if (!data.matches(DATA)) throw invalido();
        return LocalDate.parse(data);
    }

    /** Combinação válida e limite dentro da reserva: o envelope rejeita o que o núcleo recusaria por combinação. */
    private static boolean admissivel(Admissao admissao) {
        return admissao.combinacaoValida() && !admissao.limiteAcimaDaReserva();
    }

    /** Marco v4 do encerramento: correlação = causação = id do comando; id do evento derivado da causação. */
    public static byte[] marco(Comando comando, int indice, long expiradas) {
        ObjectNode raiz = cabecalhoEvento(comando, indice, 4, comando.id(), comando.encerramento().ticker());
        raiz.putObject("dados").put("expiradas", expiradas);
        raiz.put("tipo", "SESSAO_ENCERRADA");
        return raiz.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static long quantidade(JsonNode no) {
        JsonNode quantidade = no.get("quantidade");
        if (!quantidade.isIntegralNumber() || !quantidade.canConvertToLong() || quantidade.longValue() <= 0
                || quantidade.longValue() > QUANTIDADE_MAXIMA) throw invalido();
        return quantidade.longValue();
    }

    public static byte[] evento(Comando comando, Evento evento, int indice, Map<UUID, Comando> ordens) {
        int versao = versao(comando, evento, ordens);
        UUID alvo = alvo(evento);
        exigirContrato(evento, versao, ordens.get(alvo), alvo);
        UUID correlacao = versao >= 2 ? ordens.get(alvo).id() : comando.id();
        ObjectNode raiz = cabecalhoEvento(comando, indice, versao, correlacao, evento.ticker());
        dados(raiz, raiz.putObject("dados"), evento, ordens);
        byte[] bytes = raiz.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalStateException("Evento ultrapassa orçamento.");
        return bytes;
    }

    /** Agressor no negócio; nos demais fatos, a única ordem envolvida. */
    private static UUID alvo(Evento evento) {
        return switch (evento) {
            case Evento.Negocio negocio -> negocio.agressor() == Lado.COMPRA ? negocio.ordemCompra() : negocio.ordemVenda();
            default -> envolvidos(evento).getFirst();
        };
    }

    /** Fatos OCO existem somente em v3 e fatos de stop exigem admissão stop: violação indica defeito do núcleo. */
    private static void exigirContrato(Evento evento, int versao, Comando comandoDoAlvo, UUID alvo) {
        if (versao != 3 && fatoOco(evento)) throw new IllegalArgumentException("Evento OCO fora do contrato.");
        if (fatoStop(evento) && !stopPerna(comandoDoAlvo, alvo)) {
            throw new IllegalArgumentException("Evento stop sem admissão stop.");
        }
    }

    private static boolean fatoOco(Evento evento) {
        return evento instanceof Evento.OrdemReduzida
                || (evento instanceof Evento.OrdemEncerrada fim && fim.motivo() == MotivoEncerramento.CANCELADA_OCO);
    }

    private static boolean fatoStop(Evento evento) {
        return evento instanceof Evento.StopPendente || evento instanceof Evento.StopAcionado;
    }

    /** Campos comuns na ordem do contrato; causação só a partir da v2. O tipo entra depois de dados. */
    private static ObjectNode cabecalhoEvento(Comando comando, int indice, int versao, UUID correlacao, String ticker) {
        ObjectNode raiz = JSON.createObjectNode();
        raiz.put("id", uuidDeterministico("matching:" + comando.id() + ":" + indice).toString());
        raiz.put("versao", versao);
        raiz.put("origem", "matching-engine");
        raiz.put("correlacao", correlacao.toString());
        raiz.put("ocorridoEm", comando.ocorridoEm().toString());
        raiz.put("ticker", ticker);
        raiz.put("dataSessao", comando.sessao().toString());
        raiz.put("traceparent", comando.traceparent());
        if (versao >= 2) raiz.put("causacao", comando.id().toString());
        return raiz;
    }

    private static void dados(ObjectNode raiz, ObjectNode dados, Evento evento, Map<UUID, Comando> ordens) {
        switch (evento) {
            case Evento.StopPendente pendente -> {
                raiz.put("tipo", "STOP_PENDENTE");
                ordem(dados, pendente.ordem(), ordens);
                dados.put("precoGatilho", decimal(pendente.precoGatilho()));
            }
            case Evento.StopAcionado acionado -> {
                raiz.put("tipo", "STOP_ACIONADO");
                ordem(dados, acionado.ordem(), ordens);
                dados.put("tipoExecutavel", acionado.tipoExecutavel().name());
                dados.put("sequencia", acionado.sequencia());
            }
            case Evento.OrdemReduzida reduzida -> {
                raiz.put("tipo", "ORDEM_REDUZIDA");
                ordem(dados, reduzida.ordem(), ordens);
                dados.put("reduzida", reduzida.reduzida());
                dados.put("restante", reduzida.restante());
            }
            case Evento.OrdemAceita aceita -> {
                raiz.put("tipo", "ORDEM_ACEITA");
                ordem(dados, aceita.ordem(), ordens);
                dados.put("sequencia", aceita.sequencia());
            }
            case Evento.OfertaNoLivro oferta -> {
                raiz.put("tipo", "OFERTA_NO_LIVRO");
                ordem(dados, oferta.ordem(), ordens);
                dados.put("lado", oferta.lado().name());
                dados.put("preco", decimal(oferta.preco()));
                dados.put("restante", oferta.restante());
            }
            case Evento.OrdemRejeitada rejeitada -> {
                raiz.put("tipo", "ORDEM_REJEITADA");
                ordem(dados, rejeitada.ordem(), ordens);
                dados.put("motivo", rejeitada.motivo().name());
            }
            case Evento.OrdemEncerrada encerrada -> {
                raiz.put("tipo", "ORDEM_ENCERRADA");
                ordem(dados, encerrada.ordem(), ordens);
                dados.put("motivo", encerrada.motivo().name());
                dados.put("executada", encerrada.executada());
                dados.put("cancelada", encerrada.cancelada());
            }
            case Evento.Negocio negocio -> {
                raiz.put("tipo", "NEGOCIO");
                dados.put("negocio", negocio.id());
                dados.put("preco", decimal(negocio.preco()));
                dados.put("quantidade", negocio.quantidade());
                dados.put("ordemCompra", negocio.ordemCompra().toString());
                dados.put("intencaoCompra", ordens.get(negocio.ordemCompra()).admissao().intencao().toString());
                dados.put("contaCompra", negocio.contaCompra());
                dados.put("ordemVenda", negocio.ordemVenda().toString());
                dados.put("intencaoVenda", ordens.get(negocio.ordemVenda()).admissao().intencao().toString());
                dados.put("contaVenda", negocio.contaVenda());
                dados.put("agressor", negocio.agressor().name());
            }
        }
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
        return comando.perna(ordem).tipo().stop();
    }

    private static List<UUID> envolvidos(Evento evento) {
        return switch (evento) {
            case Evento.Negocio negocio -> List.of(negocio.ordemCompra(), negocio.ordemVenda());
            case Evento.OrdemAceita aceita -> List.of(aceita.ordem());
            case Evento.OrdemRejeitada rejeitada -> List.of(rejeitada.ordem());
            case Evento.OfertaNoLivro oferta -> List.of(oferta.ordem());
            case Evento.OrdemEncerrada encerrada -> List.of(encerrada.ordem());
            case Evento.StopPendente pendente -> List.of(pendente.ordem());
            case Evento.StopAcionado acionado -> List.of(acionado.ordem());
            case Evento.OrdemReduzida reduzida -> List.of(reduzida.ordem());
        };
    }

    private static void ordem(ObjectNode dados, UUID id, Map<UUID, Comando> ordens) {
        Admissao admissao = ordens.get(id).admissao();
        dados.put("ordem", id.toString());
        dados.put("intencao", admissao.intencao().toString());
        dados.put("conta", admissao.conta());
    }

    private static UUID uuidDeterministico(String semente) {
        return UUID.nameUUIDFromBytes(semente.getBytes(StandardCharsets.UTF_8));
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
