package br.prospero.mo.sessao.helper;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Comando `ENCERRAR_SESSAO` v1 (ADR-013 RF05c.4): id = causação determinística dos eventos v4. */
public final class EnvelopeEncerramento {
    public static final String TOPICO = "matching.comandos.v1";
    public static final String TIPO = "ENCERRAR_SESSAO";

    private EnvelopeEncerramento() { }

    public static UUID id(String ticker, LocalDate sessao) {
        return UUID.nameUUIDFromBytes(("encerramento:" + ticker + ":" + sessao).getBytes(StandardCharsets.UTF_8));
    }

    public static String serializar(String ticker, LocalDate sessao, Instant ocorridoEm, String traceparent) {
        var raiz = new ObjectMapper().createObjectNode();
        String id = id(ticker, sessao).toString();
        raiz.put("id", id);
        raiz.put("tipo", TIPO);
        raiz.put("versao", 1);
        raiz.put("origem", "middle-office");
        raiz.put("correlacao", id);
        raiz.put("ocorridoEm", ocorridoEm.toString());
        raiz.put("traceparent", traceparent);
        var dados = raiz.putObject("dados");
        dados.put("ticker", ticker);
        dados.put("dataSessao", sessao.toString());
        return raiz.toString();
    }
}
