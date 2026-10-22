package br.prospero.mo.matching.helper;

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
            // v2 (stops), v3 (OCO, RF05c.3b) e v4 (encerramento, RF05c.4) acrescentam a causação raiz.
            boolean v2 = versao >= 2 && versao <= 4, v3 = versao == 3, v4 = versao == 4;
            campos(r, "id,tipo,versao,origem,correlacao,ocorridoEm,ticker,dataSessao,traceparent,dados" + (v2 ? ",causacao" : ""));
            if (v2) uuid(r, "causacao");
            uuid(r, "id"); uuid(r, "correlacao");
            exigir(r.get("versao").isInt() && versao >= 1 && versao <= 4, "versao");
            // v4 transporta somente a expiração DIA e o marco do encerramento.
            exigir(!v4 || texto(r,"tipo").equals("ORDEM_ENCERRADA") || texto(r,"tipo").equals("SESSAO_ENCERRADA"), "versao");
            exigir(v4 || !texto(r,"tipo").equals("SESSAO_ENCERRADA"), "versao");
            exigir(texto(r,"origem").equals("matching-engine"), "origem");
            exigir(Set.of("ALFA3","BETA3","GAMA3","DELTA3","OMEGA3").contains(texto(r,"ticker")), "ticker");
            exigir(LocalDate.parse(texto(r,"dataSessao")).toString().equals(texto(r,"dataSessao")), "dataSessao");
            Instant.parse(texto(r,"ocorridoEm"));
            exigir(r.get("traceparent").isNull() || texto(r,"traceparent").matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"),"traceparent");
            JsonNode d = r.get("dados");
            exigirDados(r, d, v2, v3, v4);
            if (!texto(r,"tipo").equals("NEGOCIO") && !texto(r,"tipo").equals("SESSAO_ENCERRADA")) { uuid(d,"ordem"); uuid(d,"intencao"); conta(d,"conta"); }
            return r;
        } catch (MensagemInvalidaException e) { throw e; }
        catch (Exception e) { throw invalida("envelope"); }
    }

    /** Campos e limites de cada tipo de fato; v2 habilita stops, v3 OCO e v4 a expiração. Chamado dentro do try de ler. */
    private static void exigirDados(JsonNode r, JsonNode d, boolean v2, boolean v3, boolean v4) {
        switch (texto(r,"tipo")) {
            case "ORDEM_ACEITA" -> { campos(d,"ordem,intencao,conta,sequencia"); inteiro(d,"sequencia",1,Long.MAX_VALUE); }
            case "STOP_PENDENTE" -> { exigir(v2,"versao"); campos(d,"ordem,intencao,conta,precoGatilho"); preco(d,"precoGatilho"); }
            case "STOP_ACIONADO" -> { exigir(v2,"versao"); campos(d,"ordem,intencao,conta,tipoExecutavel,sequencia"); exigir(Set.of("MARKET","LIMIT").contains(texto(d,"tipoExecutavel")),"tipoExecutavel"); inteiro(d,"sequencia",1,Long.MAX_VALUE); }
            case "SESSAO_ENCERRADA" -> { campos(d,"expiradas"); inteiro(d,"expiradas",0,1000000); }
            case "ORDEM_REDUZIDA" -> { exigir(v3,"versao"); campos(d,"ordem,intencao,conta,reduzida,restante"); inteiro(d,"reduzida",1,1000000); inteiro(d,"restante",1,1000000); }
            case "OFERTA_NO_LIVRO" -> { campos(d,"ordem,intencao,conta,lado,preco,restante"); exigir(Set.of("COMPRA","VENDA").contains(texto(d,"lado")),"lado"); preco(d,"preco"); inteiro(d,"restante",1,1000000); }
            case "ORDEM_REJEITADA" -> { campos(d,"ordem,intencao,conta,motivo"); exigir(Set.of("ATIVO_DESCONHECIDO","COMBINACAO_INVALIDA","PRECO_ACIMA_DA_RESERVA","PRECO_INVALIDO","QUANTIDADE_INVALIDA","SESSAO_ANTERIOR","SESSAO_ENCERRADA","SESSAO_NAO_ENCERRADA","LIMITE_ORDENS_ATIVAS").contains(texto(d,"motivo")) || v2 && texto(d,"motivo").equals("LIMITE_STOPS_PENDENTES"),"motivo"); }
            case "ORDEM_ENCERRADA" -> { campos(d,"ordem,intencao,conta,motivo,executada,cancelada"); exigir(v4 == texto(d,"motivo").equals("EXPIRADA_DIA"),"motivo"); exigir(v4 || Set.of("EXECUTADA","SALDO_CANCELADO_IOC","FOK_SEM_LIQUIDEZ","FORA_DA_FAIXA","LIMITADA_PELA_RESERVA","SEM_LIQUIDEZ","LIMITE_NEGOCIOS").contains(texto(d,"motivo")) || v2 && texto(d,"motivo").equals("LIMITE_CASCATA") || v3 && texto(d,"motivo").equals("CANCELADA_OCO"),"motivo"); inteiro(d,"executada",0,1000000); inteiro(d,"cancelada",0,1000000); }
            case "NEGOCIO" -> {
                campos(d,"negocio,preco,quantidade,ordemCompra,intencaoCompra,contaCompra,ordemVenda,intencaoVenda,contaVenda,agressor");
                exigir(texto(d,"negocio").matches("[A-Z0-9]+-\\d{4}-\\d{2}-\\d{2}-[1-9][0-9]*"),"negocio");
                exigir(texto(d,"negocio").startsWith(texto(r,"ticker")+"-"+texto(r,"dataSessao")+"-"),"negocio");
                preco(d,"preco"); inteiro(d,"quantidade",1,1000000);
                for (String lado : Set.of("Compra","Venda")) { uuid(d,"ordem"+lado); uuid(d,"intencao"+lado); conta(d,"conta"+lado); }
                exigir(Set.of("COMPRA","VENDA").contains(texto(d,"agressor")),"agressor");
            }
            default -> throw invalida("tipo");
        }
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
