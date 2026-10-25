package br.prospero.bo.acompanhamento.helper;

import br.prospero.bo.posicoes.dominio.EventoMatching;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Contrato RF09: confirma somente um negócio efetivamente contabilizado no BO. */
public final class EventoContabilizacao {
    public static final String TOPICO = "acompanhamento.eventos.v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private EventoContabilizacao() { }

    public static UUID id(String negocio) {
        return UUID.nameUUIDFromBytes(("acompanhamento:" + negocio).getBytes(StandardCharsets.UTF_8));
    }

    public static String criar(EventoMatching evento, Instant contabilizadoEm) {
        if (!(evento.detalhe() instanceof EventoMatching.Negocio negocio)) {
            throw new IllegalArgumentException("Confirmação exige negócio.");
        }
        var raiz = new LinkedHashMap<String, Object>();
        raiz.put("id", id(negocio.id()).toString());
        raiz.put("tipo", "NEGOCIO_CONTABILIZADO");
        raiz.put("versao", 1);
        raiz.put("origem", "back-office");
        raiz.put("causacao", evento.id().toString());
        raiz.put("ocorridoEm", contabilizadoEm.toString());
        raiz.put("traceparent", evento.traceparent());
        raiz.put("dados", Map.of("negocio", negocio.id(), "ticker", evento.ticker(),
                "quantidade", Long.toString(negocio.quantidade()),
                "ordemCompra", negocio.compra().ordem().toString(), "contaCompra", negocio.compra().conta(),
                "ordemVenda", negocio.venda().ordem().toString(), "contaVenda", negocio.venda().conta()));
        try { return JSON.writeValueAsString(raiz); }
        catch (com.fasterxml.jackson.core.JsonProcessingException erro) { throw new IllegalStateException("Contrato inválido", erro); }
    }
}
