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

    public record Comando(UUID id, Instant ocorridoEm, String traceparent, Admissao admissao) { }

    public static Comando comando(byte[] bytes, String chave) {
        try {
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalido();
            JsonNode raiz = JSON.readTree(bytes);
            campos(raiz, RAIZ);
            if (!texto(raiz, "tipo").equals("ORDEM_ADMITIDA") || !raiz.get("versao").isInt()
                    || raiz.get("versao").intValue() != 1 || !texto(raiz, "origem").equals("middle-office")) throw invalido();
            UUID id = uuid(raiz, "id"), intencao = uuid(raiz, "correlacao");
            if (!id.equals(UUID.nameUUIDFromBytes(("admissao:" + intencao).getBytes(StandardCharsets.UTF_8)))) throw invalido();
            String conta = texto(raiz, "conta");
            if (!Set.of("CONTA-001", "CONTA-002", "CONTA-003").contains(conta)) throw invalido();
            String trace = raiz.get("traceparent").isNull() ? null : texto(raiz, "traceparent");
            if (trace != null && (!trace.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                    || trace.substring(3, 35).equals("0".repeat(32)) || trace.substring(36, 52).equals("0".repeat(16)))) throw invalido();
            Instant ocorrido = Instant.parse(texto(raiz, "ocorridoEm"));
            JsonNode dados = raiz.get("dados");
            campos(dados, DADOS);
            String ticker = texto(dados, "ticker");
            if (!ATIVOS.contains(ticker) || !ticker.equals(chave)) throw invalido();
            JsonNode q = dados.get("quantidade");
            if (!q.isIntegralNumber() || !q.canConvertToLong() || q.longValue() <= 0 || q.longValue() > 1_000_000) throw invalido();
            String data = texto(dados, "dataSessao");
            if (!data.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalido();
            Admissao a = new Admissao(uuid(dados, "ordem"), intencao, conta, ticker, Lado.valueOf(texto(dados, "lado")),
                    TipoOrdem.valueOf(texto(dados, "tipo")), Validade.valueOf(texto(dados, "validade")), q.longValue(),
                    preco(dados, "precoLimite"), preco(dados, "precoMaximo"), LocalDate.parse(data));
            boolean compra = a.lado() == Lado.COMPRA, limit = a.tipo() == TipoOrdem.LIMIT;
            if (limit != (a.precoLimite() != null) || compra != (a.precoMaximo() != null)
                    || (!limit && a.validade() == Validade.DIA)
                    || (compra && limit && a.precoLimite().compareTo(a.precoMaximo()) > 0)) throw invalido();
            return new Comando(id, ocorrido, trace, a);
        } catch (Exception erro) {
            // Não incluir conteúdo, IDs ou exceções de parser no diagnóstico.
            throw invalido();
        }
    }

    public static byte[] evento(Comando comando, Evento evento, int indice, Map<UUID, Comando> ordens) {
        ObjectNode raiz = JSON.createObjectNode();
        raiz.put("id", UUID.nameUUIDFromBytes(("matching:" + comando.id() + ":" + indice).getBytes(StandardCharsets.UTF_8)).toString());
        raiz.put("versao", 1);
        raiz.put("origem", "matching-engine");
        raiz.put("correlacao", comando.id().toString());
        raiz.put("ocorridoEm", comando.ocorridoEm().toString());
        raiz.put("ticker", evento.ticker());
        raiz.put("dataSessao", comando.admissao().sessao().toString());
        raiz.put("traceparent", comando.traceparent());
        ObjectNode dados = raiz.putObject("dados");
        switch (evento) {
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
