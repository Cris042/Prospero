package br.prospero.bo.liquidacao.helper;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Contrato v1 do ADR-016; nenhuma classe do MO é importada. Serialização estável persistida antes do relay. */
public final class EventoCredito {
    private static final ObjectMapper JSON = new ObjectMapper();
    public record Origem(UUID causacao, Instant instante) { }
    private EventoCredito() { }
    public static UUID id(String negocio, String lado) {
        if (!"COMPRADOR".equals(lado) && !"VENDEDOR".equals(lado)) throw new IllegalArgumentException("Lado inválido");
        return UUID.nameUUIDFromBytes(("liquidacao:" + negocio + ":" + lado).getBytes(StandardCharsets.UTF_8));
    }
    public static String criar(String negocio, String lado, String conta, String ticker, String valor,
                               LocalDate data, Origem origem) {
        var envelope = new LinkedHashMap<String,Object>();
        envelope.put("id",id(negocio,lado).toString());
        envelope.put("tipo","LIQUIDACAO_EFETIVADA");
        envelope.put("versao",1);
        envelope.put("origem","back-office");
        envelope.put("causacao",origem.causacao().toString());
        envelope.put("ocorridoEm",origem.instante().toString());
        envelope.put("traceparent",null);
        envelope.put("dados",Map.of("negocio",negocio,"lado",lado,"conta",conta,"ticker",ticker,"valor",valor,
                "recurso",lado.equals("COMPRADOR") ? "CUSTODIA" : "CAIXA","dataLiquidacao",data.toString()));
        try { return JSON.writeValueAsString(envelope); }
        catch (com.fasterxml.jackson.core.JsonProcessingException erro) { throw new IllegalStateException("Contrato inválido",erro); }
    }
}
