package br.prospero.mo.liquidacao;

import br.prospero.mo.liquidacao.dominio.CreditoEsperado;
import br.prospero.mo.liquidacao.dominio.LadoLiquidacao;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.UUID;

/** LIQUIDACAO_EFETIVADA v1 sintético (ainda não há emissor no BO): o mesmo formato que a RF08d publicará. */
public final class EventosSinteticos {
    public static final ObjectMapper JSON = new ObjectMapper();

    private EventosSinteticos() {
    }

    public static ObjectNode evento(String negocio, LadoLiquidacao lado, String conta, String ticker, String valor,
                                    UUID causacao) {
        ObjectNode e = JSON.createObjectNode();
        e.put("id", CreditoEsperado.id(negocio, lado).toString());
        e.put("tipo", "LIQUIDACAO_EFETIVADA");
        e.put("versao", 1);
        e.put("origem", "back-office");
        e.put("causacao", causacao.toString());
        e.put("ocorridoEm", "2026-10-20T15:00:00Z");
        e.putNull("traceparent");
        ObjectNode d = e.putObject("dados");
        d.put("negocio", negocio);
        d.put("lado", lado.name());
        d.put("conta", conta);
        d.put("recurso", lado.recurso().name());
        d.put("ticker", ticker);
        d.put("valor", valor);
        d.put("dataLiquidacao", "2026-10-20");
        return e;
    }

    public static byte[] bytes(ObjectNode evento) {
        try {
            return JSON.writeValueAsBytes(evento);
        } catch (Exception erro) {
            throw new IllegalStateException(erro);
        }
    }
}
