package br.prospero.mo.matching.helper;

import br.prospero.mo.matching.dominio.FatoMatching;
import br.prospero.mo.mensageria.dominio.MensagemInvalidaException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Entrada estrita; erros contêm somente o nome do campo, nunca o payload. */
public final class EnvelopeMatching {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private EnvelopeMatching() { }

    public static JsonNode ler(byte[] bytes) {
        try {
            exigir(bytes != null && bytes.length <= 16384, "tamanho");
            JsonNode r = JSON.readTree(bytes);
            int versao = r != null && r.path("versao").isInt() ? r.get("versao").intValue() : 0;
            // v2 (stops), v3 (OCO, RF05c.3b), v4 (encerramento, RF05c.4) e v5 (cancelamento manual, RF10c2) acrescentam a causação raiz.
            boolean v2 = versao >= 2 && versao <= 5, v3 = versao == 3, v4 = versao == 4, v5 = versao == 5;
            campos(r, "id,tipo,versao,origem,correlacao,ocorridoEm,ticker,dataSessao,traceparent,dados" + (v2 ? ",causacao" : ""));
            if (v2) uuid(r, "causacao");
            uuid(r, "id"); uuid(r, "correlacao");
            exigirEnvelope(r, versao, v4);
            JsonNode d = r.get("dados");
            exigirVersao5(r, d, v5);
            exigirDados(r, d, v2, v3, v4, v5);
            exigirAlvo(r, d);
            if (v5) exigirDerivacaoV5(r, d);
            return r;
        } catch (MensagemInvalidaException e) { throw e; }
        catch (Exception e) { throw invalida("envelope"); }
    }

    private static void exigirEnvelope(JsonNode r, int versao, boolean v4) {
        exigir(r.get("versao").isInt() && versao >= 1 && versao <= 5, "versao");
        // v4 transporta somente a expiração DIA e o marco do encerramento.
        exigir(!v4 || texto(r,"tipo").equals("ORDEM_ENCERRADA") || texto(r,"tipo").equals("SESSAO_ENCERRADA"), "versao");
        exigir(v4 || !texto(r,"tipo").equals("SESSAO_ENCERRADA"), "versao");
        exigir(texto(r,"origem").equals("matching-engine"), "origem");
        exigir(Set.of("ALFA3","BETA3","GAMA3","DELTA3","OMEGA3").contains(texto(r,"ticker")), "ticker");
        exigir(LocalDate.parse(texto(r,"dataSessao")).toString().equals(texto(r,"dataSessao")), "dataSessao");
        Instant.parse(texto(r,"ocorridoEm"));
        exigir(r.get("traceparent").isNull() || texto(r,"traceparent").matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"),"traceparent");
    }

    private static void exigirVersao5(JsonNode r, JsonNode d, boolean v5) {
        // v5 transporta somente o terminal CANCELADA_MANUAL e a decisão do cancelamento.
        exigir(v5 == (texto(r,"tipo").equals("CANCELAMENTO_DECIDIDO") || texto(r,"tipo").equals("ORDEM_ENCERRADA")
                && d != null && d.path("motivo").isTextual() && d.get("motivo").textValue().equals("CANCELADA_MANUAL")), "versao");
    }

    private static void exigirAlvo(JsonNode r, JsonNode d) {
        if (!texto(r,"tipo").equals("NEGOCIO") && !texto(r,"tipo").equals("SESSAO_ENCERRADA")) { uuid(d,"ordem"); uuid(d,"intencao"); conta(d,"conta"); }
    }

    /** Campos e limites de cada tipo de fato; v2 habilita stops, v3 OCO e v4 a expiração. Chamado dentro do try de ler. */
    private static void exigirDados(JsonNode r, JsonNode d, boolean v2, boolean v3, boolean v4, boolean v5) {
        switch (texto(r,"tipo")) {
            case "ORDEM_ACEITA" -> { campos(d,"ordem,intencao,conta,sequencia"); inteiro(d,"sequencia",1,Long.MAX_VALUE); }
            case "STOP_PENDENTE" -> { exigir(v2,"versao"); campos(d,"ordem,intencao,conta,precoGatilho"); preco(d,"precoGatilho"); }
            case "STOP_ACIONADO" -> { exigir(v2,"versao"); campos(d,"ordem,intencao,conta,tipoExecutavel,sequencia"); exigir(Set.of("MARKET","LIMIT").contains(texto(d,"tipoExecutavel")),"tipoExecutavel"); inteiro(d,"sequencia",1,Long.MAX_VALUE); }
            case "SESSAO_ENCERRADA" -> { campos(d,"expiradas"); inteiro(d,"expiradas",0,1000000); }
            case "ORDEM_REDUZIDA" -> { exigir(v3,"versao"); campos(d,"ordem,intencao,conta,reduzida,restante"); inteiro(d,"reduzida",1,1000000); inteiro(d,"restante",1,1000000); }
            case "OFERTA_NO_LIVRO" -> { campos(d,"ordem,intencao,conta,lado,preco,restante"); exigir(Set.of("COMPRA","VENDA").contains(texto(d,"lado")),"lado"); preco(d,"preco"); inteiro(d,"restante",1,1000000); }
            case "ORDEM_REJEITADA" -> { campos(d,"ordem,intencao,conta,motivo"); exigirMotivoRejeitada(d, v2); }
            case "ORDEM_ENCERRADA" -> exigirEncerrada(d, v2, v3, v4, v5);
            case "CANCELAMENTO_DECIDIDO" -> {
                exigir(v5,"versao"); campos(d,"ordem,intencao,conta,resultado,terminal");
                String resultado = texto(d,"resultado");
                exigir(Set.of("APLICADO","SEM_SALDO_VIVO","ALVO_DIVERGENTE","ORIGEM_OCO").contains(resultado),"resultado");
                exigir(resultado.equals("APLICADO") == !d.get("terminal").isNull(),"terminal");
                if (!d.get("terminal").isNull()) uuid(d,"terminal");
            }
            case "NEGOCIO" -> exigirNegocio(r, d);
            default -> throw invalida("tipo");
        }
    }

    private static void exigirMotivoRejeitada(JsonNode d, boolean v2) {
        exigir(Set.of("ATIVO_DESCONHECIDO","COMBINACAO_INVALIDA","PRECO_ACIMA_DA_RESERVA","PRECO_INVALIDO","QUANTIDADE_INVALIDA","SESSAO_ANTERIOR","SESSAO_ENCERRADA","SESSAO_NAO_ENCERRADA","LIMITE_ORDENS_ATIVAS").contains(texto(d,"motivo")) || v2 && texto(d,"motivo").equals("LIMITE_STOPS_PENDENTES"),"motivo");
    }

    private static void exigirEncerrada(JsonNode d, boolean v2, boolean v3, boolean v4, boolean v5) {
        campos(d,"ordem,intencao,conta,motivo,executada,cancelada"); exigir(v4 == texto(d,"motivo").equals("EXPIRADA_DIA"),"motivo");
        exigir(v4 || v5 && texto(d,"motivo").equals("CANCELADA_MANUAL") || Set.of("EXECUTADA","SALDO_CANCELADO_IOC","FOK_SEM_LIQUIDEZ","FORA_DA_FAIXA","LIMITADA_PELA_RESERVA","SEM_LIQUIDEZ","LIMITE_NEGOCIOS").contains(texto(d,"motivo")) || v2 && texto(d,"motivo").equals("LIMITE_CASCATA") || v3 && texto(d,"motivo").equals("CANCELADA_OCO"),"motivo");
        long executada = inteiro(d,"executada",0,1000000), cancelada = inteiro(d,"cancelada",v5 ? 1 : 0,1000000);
        exigir(!v5 || executada + cancelada <= 1000000,"cancelada");
    }

    private static void exigirNegocio(JsonNode r, JsonNode d) {
        campos(d,"negocio,preco,quantidade,ordemCompra,intencaoCompra,contaCompra,ordemVenda,intencaoVenda,contaVenda,agressor");
        exigir(texto(d,"negocio").matches("[A-Z0-9]+-\\d{4}-\\d{2}-\\d{2}-[1-9][0-9]*"),"negocio");
        exigir(texto(d,"negocio").startsWith(texto(r,"ticker")+"-"+texto(r,"dataSessao")+"-"),"negocio");
        preco(d,"preco"); inteiro(d,"quantidade",1,1000000);
        for (String lado : Set.of("Compra","Venda")) { uuid(d,"ordem"+lado); uuid(d,"intencao"+lado); conta(d,"conta"+lado); }
        exigir(Set.of("COMPRA","VENDA").contains(texto(d,"agressor")),"agressor");
    }

    /** Campos do fato de ordem conforme o tipo, já validados com os mesmos limites por {@link #ler}. */
    public static FatoMatching fato(JsonNode evento, JsonNode d) {
        String tipo=texto(evento,"tipo");
        UUID correlacao=uuid(evento,"correlacao");
        return switch (tipo) {
            case "STOP_PENDENTE" -> FatoMatching.stopPendente(correlacao,preco(d,"precoGatilho"));
            case "STOP_ACIONADO" -> FatoMatching.stopAcionado(correlacao,texto(d,"tipoExecutavel"));
            case "ORDEM_REJEITADA" -> FatoMatching.ordemRejeitada(correlacao,texto(d,"motivo"));
            case "ORDEM_ENCERRADA" -> FatoMatching.ordemEncerrada(correlacao,texto(d,"motivo"),
                    inteiro(d,"executada",0,1000000),inteiro(d,"cancelada",0,1000000));
            case "OFERTA_NO_LIVRO" -> FatoMatching.ofertaNoLivro(correlacao,inteiro(d,"restante",1,1000000),
                    preco(d,"preco"),texto(d,"lado"));
            case "ORDEM_REDUZIDA" -> FatoMatching.ordemReduzida(correlacao,inteiro(d,"reduzida",1,1000000),
                    inteiro(d,"restante",1,1000000));
            default -> FatoMatching.semCampos(tipo,correlacao);
        };
    }

    private static UUID derivado(String semente) { return UUID.nameUUIDFromBytes(semente.getBytes(StandardCharsets.UTF_8)); }

    /**
     * Identidade derivada do comando de cancelamento: causação, correlação (admissão alvo) e ids dos eventos
     * ({@code matching:<comando>:<índice>}, terminal no índice 0 e decisão por último). Chamado dentro do try de ler.
     */
    private static void exigirDerivacaoV5(JsonNode r, JsonNode d) {
        UUID comando = derivado("cancelamento:" + uuid(d,"ordem"));
        boolean decisao = texto(r,"tipo").equals("CANCELAMENTO_DECIDIDO");
        boolean aplicado = decisao && texto(d,"resultado").equals("APLICADO");
        exigir(uuid(r,"causacao").equals(comando),"causacao");
        exigir(uuid(r,"correlacao").equals(derivado("admissao:" + uuid(d,"intencao"))),"correlacao");
        exigir(uuid(r,"id").equals(derivado("matching:" + comando + ":" + (aplicado ? 1 : 0))),"id");
        if (aplicado) exigir(uuid(d,"terminal").equals(derivado("matching:" + comando + ":0")),"terminal");
    }

    private static void motivo(JsonNode d) { exigir(texto(d,"motivo").matches("[A-Z_]{1,64}"),"motivo"); }
    private static void conta(JsonNode d,String c) { exigir(Set.of("CONTA-001","CONTA-002","CONTA-003","CONTA-004").contains(texto(d,c)),c); }
    public static void campos(JsonNode r,String nomes) {
        Set<String> campos = Set.of(nomes.split(","));
        exigir(r != null && r.isObject() && r.size() == campos.size(),"campos");
        r.fieldNames().forEachRemaining(c -> exigir(campos.contains(c),"campos"));
    }
    public static String texto(JsonNode r,String c) { exigir(r.get(c)!=null && r.get(c).isTextual(),c); return r.get(c).textValue(); }
    public static UUID uuid(JsonNode r,String c) { UUID u=UUID.fromString(texto(r,c)); exigir(u.toString().equals(texto(r,c)),c); return u; }
    public static long inteiro(JsonNode r,String c,long min,long max) { JsonNode n=r.get(c); exigir(n!=null && n.isIntegralNumber() && n.canConvertToLong() && n.longValue()>=min && n.longValue()<=max,c); return n.longValue(); }
    public static BigDecimal preco(JsonNode r,String c) { String s=texto(r,c); exigir(s.matches("[0-9]{1,7}\\.[0-9]{2}"),c); BigDecimal p=new BigDecimal(s); exigir(p.signum()>0 && p.compareTo(new BigDecimal("1000000.00"))<=0,c); return p; }
    /** Causação determinística do encerramento de um ativo/data (ADR-013 RF05c.4). */
    public static UUID encerramento(String ticker, String dataSessao) {
        return UUID.nameUUIDFromBytes(("encerramento:" + ticker + ":" + dataSessao).getBytes(StandardCharsets.UTF_8));
    }
    public static void exigir(boolean condicao,String campo) { if (!condicao) throw invalida(campo); }
    private static MensagemInvalidaException invalida(String campo) { return new MensagemInvalidaException("matching: "+campo); }
    private static JsonNode canonico(JsonNode r) {
        if (r.isObject()) {
            var resultado=JSON.createObjectNode();
            var nomes=new java.util.TreeSet<String>();
            r.fieldNames().forEachRemaining(nomes::add);
            nomes.forEach(n -> resultado.set(n,canonico(r.get(n))));
            return resultado;
        }
        if (r.isArray()) {
            var resultado=JSON.createArrayNode();
            r.forEach(n -> resultado.add(canonico(n)));
            return resultado;
        }
        return r;
    }
    public static String assinatura(JsonNode r) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonico(r).toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
