package br.prospero.mo.risco.helper;

import br.prospero.mo.risco.dominio.ComandoRisco;
import br.prospero.mo.risco.dominio.Decisao;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Contrato determinístico da admissão; usado somente após persistir a reserva. */
public final class EnvelopeAdmissao {
    private EnvelopeAdmissao() { }

    public static UUID id(UUID intencao) {
        return UUID.nameUUIDFromBytes(("admissao:" + intencao).getBytes(StandardCharsets.UTF_8));
    }

    public static String serializar(ComandoRisco c, Decisao d, String traceparent) {
        var raiz = new ObjectMapper().createObjectNode();
        raiz.put("id", id(c.intencao()).toString());
        raiz.put("tipo", "ORDEM_ADMITIDA");
        var p = c.pernas().getFirst();
        boolean stop = p.tipo() == br.prospero.mo.risco.dominio.TipoOrdem.STOP
                || p.tipo() == br.prospero.mo.risco.dominio.TipoOrdem.STOP_LIMIT;
        raiz.put("versao", stop ? 2 : 1);
        raiz.put("origem", "middle-office");
        raiz.put("correlacao", c.intencao().toString());
        raiz.put("ocorridoEm", d.decididaEm().toString());
        raiz.put("conta", c.conta());
        raiz.put("traceparent", traceparent);
        var dados = raiz.putObject("dados");
        dados.put("ordem", p.id().toString());
        dados.put("ticker", c.ticker());
        dados.put("lado", c.lado().name());
        dados.put("tipo", p.tipo().name());
        dados.put("validade", p.validade().name());
        dados.put("quantidade", p.quantidade());
        dados.put("precoLimite", p.precoLimite() == null ? null : p.precoLimite().setScale(2).toPlainString());
        dados.put("precoMaximo", d.valorReservado() == null ? null : d.valorReservado()
                .divide(BigDecimal.valueOf(p.quantidade())).setScale(2).toPlainString());
        dados.put("dataSessao", c.dataSessao().toString());
        if (stop) dados.put("precoGatilho", p.precoGatilho().setScale(2).toPlainString());
        return raiz.toString();
    }
}
