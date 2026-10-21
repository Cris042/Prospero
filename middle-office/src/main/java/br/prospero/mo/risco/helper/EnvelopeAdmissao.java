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

    /** Preço unitário máximo exato da compra: reserva ÷ quantidade (o par tem a mesma quantidade nas pernas). */
    public static BigDecimal precoMaximo(ComandoRisco c, Decisao d) {
        return d.valorReservado() == null ? null
                : d.valorReservado().divide(BigDecimal.valueOf(c.pernas().getFirst().quantidade()), 2,
                        java.math.RoundingMode.UNNECESSARY);
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
        if (c.oco()) {
            // v3 (ADR-013 RF05c.3b): uma mensagem para o par, TP antes do SL.
            raiz.put("versao", 3);
            dados.put("ticker", c.ticker());
            dados.put("lado", c.lado().name());
            dados.put("dataSessao", c.dataSessao().toString());
            BigDecimal maximo = precoMaximo(c, d);
            dados.put("precoMaximo", maximo == null ? null : maximo.setScale(2).toPlainString());
            var pernas = dados.putArray("pernas");
            String[] papeis = {"TAKE_PROFIT", "STOP_LOSS"};
            for (int i = 0; i < 2; i++) {
                var perna = c.pernas().get(i);
                var no = pernas.addObject();
                no.put("papel", papeis[i]);
                no.put("ordem", perna.id().toString());
                no.put("tipo", perna.tipo().name());
                no.put("validade", perna.validade().name());
                no.put("quantidade", perna.quantidade());
                no.put("precoLimite", perna.precoLimite() == null ? null : perna.precoLimite().setScale(2).toPlainString());
                if (i == 1) no.put("precoGatilho", perna.precoGatilho().setScale(2).toPlainString());
            }
            return raiz.toString();
        }
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
