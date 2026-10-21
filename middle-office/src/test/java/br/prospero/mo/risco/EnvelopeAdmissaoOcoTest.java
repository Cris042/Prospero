package br.prospero.mo.risco;

import br.prospero.mo.risco.dominio.ComandoRisco;
import br.prospero.mo.risco.dominio.Decisao;
import br.prospero.mo.risco.dominio.Lado;
import br.prospero.mo.risco.dominio.Perna;
import br.prospero.mo.risco.dominio.TipoOrdem;
import br.prospero.mo.risco.dominio.Validade;
import br.prospero.mo.risco.helper.CalculoReserva;
import br.prospero.mo.risco.helper.EnvelopeAdmissao;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RF05c.3b2 (QA): admissão v3 do par fixada byte a byte. Os mesmos literais são lidos pelo parser v3 do ME em
 * {@code AdmissaoOcoContratoMoTest}; alterar um lado exige alterar o outro.
 */
class EnvelopeAdmissaoOcoTest {
    static final Instant DECIDIDA = Instant.parse("2026-10-18T12:00:00Z");
    static final LocalDate SESSAO = LocalDate.of(2026, 10, 18);
    static final String TRACE = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    static final String COMPRA_STOP = "{\"id\":\"cdc25fd2-7b6d-32cb-83c0-b11821e36367\",\"tipo\":\"ORDEM_ADMITIDA\",\"versao\":3,"
            + "\"origem\":\"middle-office\",\"correlacao\":\"00000000-0000-0000-0000-0000000000a1\","
            + "\"ocorridoEm\":\"2026-10-18T12:00:00Z\",\"conta\":\"CONTA-002\",\"traceparent\":\"" + TRACE + "\","
            + "\"dados\":{\"ticker\":\"ALFA3\",\"lado\":\"COMPRA\",\"dataSessao\":\"2026-10-18\",\"precoMaximo\":\"11.22\","
            + "\"pernas\":[{\"papel\":\"TAKE_PROFIT\",\"ordem\":\"00000000-0000-0000-0000-0000000000b1\",\"tipo\":\"LIMIT\","
            + "\"validade\":\"DIA\",\"quantidade\":2,\"precoLimite\":\"9.80\"},{\"papel\":\"STOP_LOSS\","
            + "\"ordem\":\"00000000-0000-0000-0000-0000000000c1\",\"tipo\":\"STOP\",\"validade\":\"DIA\",\"quantidade\":2,"
            + "\"precoLimite\":null,\"precoGatilho\":\"10.20\"}]}}";

    static final String COMPRA_STOP_LIMIT = "{\"id\":\"3d660176-afef-3173-81c2-085a5b5aea42\",\"tipo\":\"ORDEM_ADMITIDA\","
            + "\"versao\":3,\"origem\":\"middle-office\",\"correlacao\":\"00000000-0000-0000-0000-0000000000a2\","
            + "\"ocorridoEm\":\"2026-10-18T12:00:00Z\",\"conta\":\"CONTA-002\",\"traceparent\":null,"
            + "\"dados\":{\"ticker\":\"ALFA3\",\"lado\":\"COMPRA\",\"dataSessao\":\"2026-10-18\",\"precoMaximo\":\"10.30\","
            + "\"pernas\":[{\"papel\":\"TAKE_PROFIT\",\"ordem\":\"00000000-0000-0000-0000-0000000000b2\",\"tipo\":\"LIMIT\","
            + "\"validade\":\"DIA\",\"quantidade\":2,\"precoLimite\":\"9.80\"},{\"papel\":\"STOP_LOSS\","
            + "\"ordem\":\"00000000-0000-0000-0000-0000000000c2\",\"tipo\":\"STOP_LIMIT\",\"validade\":\"DIA\",\"quantidade\":2,"
            + "\"precoLimite\":\"10.30\",\"precoGatilho\":\"10.20\"}]}}";

    static final String VENDA_STOP = "{\"id\":\"88ecb117-8ebf-31d3-a9d7-aa13cf4434e4\",\"tipo\":\"ORDEM_ADMITIDA\",\"versao\":3,"
            + "\"origem\":\"middle-office\",\"correlacao\":\"00000000-0000-0000-0000-0000000000a3\","
            + "\"ocorridoEm\":\"2026-10-18T12:00:00Z\",\"conta\":\"CONTA-003\",\"traceparent\":null,"
            + "\"dados\":{\"ticker\":\"ALFA3\",\"lado\":\"VENDA\",\"dataSessao\":\"2026-10-18\",\"precoMaximo\":null,"
            + "\"pernas\":[{\"papel\":\"TAKE_PROFIT\",\"ordem\":\"00000000-0000-0000-0000-0000000000b3\",\"tipo\":\"LIMIT\","
            + "\"validade\":\"DIA\",\"quantidade\":3,\"precoLimite\":\"10.20\"},{\"papel\":\"STOP_LOSS\","
            + "\"ordem\":\"00000000-0000-0000-0000-0000000000c3\",\"tipo\":\"STOP\",\"validade\":\"DIA\",\"quantidade\":3,"
            + "\"precoLimite\":null,\"precoGatilho\":\"9.80\"}]}}";

    static UUID id(String sufixo) {
        return UUID.fromString("00000000-0000-0000-0000-0000000000" + sufixo);
    }

    static BigDecimal preco(String valor) {
        return valor == null ? null : new BigDecimal(valor);
    }

    static ComandoRisco par(String n, String conta, Lado lado, long q, String alvo, TipoOrdem tipoStop, String limiteStop,
                            String gatilho) {
        var tp = new Perna(id("b" + n), TipoOrdem.LIMIT, q, preco(alvo), null, Validade.DIA);
        var sl = new Perna(id("c" + n), tipoStop, q, preco(limiteStop), preco(gatilho), Validade.DIA);
        return new ComandoRisco(id("e" + n), id("a" + n), conta, "ALFA3", lado, new BigDecimal("10.00"), List.of(tp, sl),
                null, SESSAO);
    }

    static Decisao decidir(ComandoRisco c) {
        return Decisao.reservada(c, CalculoReserva.exigencia(c, CalculoReservaTest.ALFA), 1, DECIDIDA);
    }

    @Test
    void compraComStopReservaPeloTetoDoGatilhoESerializaV3Exato() {
        var c = par("1", "CONTA-002", Lado.COMPRA, 2, "9.80", TipoOrdem.STOP, null, "10.20");
        assertTrue(c.admissivel());
        var d = decidir(c);
        assertEquals(0, new BigDecimal("22.44").compareTo(d.valorReservado()));
        assertEquals(COMPRA_STOP, EnvelopeAdmissao.serializar(c, d, TRACE));
    }

    @Test
    void compraComStopLimitUsaOLimiteDoStopComoPrecoMaximoComum() {
        var c = par("2", "CONTA-002", Lado.COMPRA, 2, "9.80", TipoOrdem.STOP_LIMIT, "10.30", "10.20");
        assertTrue(c.admissivel());
        var d = decidir(c);
        assertEquals(0, new BigDecimal("20.60").compareTo(d.valorReservado()));
        assertEquals(0, new BigDecimal("10.30").compareTo(EnvelopeAdmissao.precoMaximo(c, d)));
        assertEquals(COMPRA_STOP_LIMIT, EnvelopeAdmissao.serializar(c, d, null));
    }

    @Test
    void vendaReservaCustodiaUmaVezESerializaPrecoMaximoNulo() {
        var c = par("3", "CONTA-003", Lado.VENDA, 3, "10.20", TipoOrdem.STOP, null, "9.80");
        assertTrue(c.admissivel());
        var d = decidir(c);
        assertEquals(3L, d.quantidadeReservada());
        assertNull(EnvelopeAdmissao.precoMaximo(c, d));
        assertEquals(VENDA_STOP, EnvelopeAdmissao.serializar(c, d, null));
    }

    @Test
    void parForaDoFormatoNaoEhOcoNemAdmissivel() {
        var base = par("4", "CONTA-002", Lado.COMPRA, 2, "9.80", TipoOrdem.STOP, null, "10.20");
        var tp = base.pernas().get(0);
        var sl = base.pernas().get(1);
        List<List<Perna>> invalidos = List.of(
                List.of(new Perna(tp.id(), TipoOrdem.MARKET, 2, null, null, Validade.DIA), sl),
                List.of(new Perna(tp.id(), TipoOrdem.LIMIT, 2, tp.precoLimite(), null, Validade.IOC), sl),
                List.of(new Perna(tp.id(), TipoOrdem.LIMIT, 2, tp.precoLimite(), null, null), sl),
                List.of(tp, new Perna(sl.id(), TipoOrdem.LIMIT, 2, new BigDecimal("10.20"), null, Validade.DIA)),
                List.of(tp, new Perna(sl.id(), TipoOrdem.STOP, 2, null, sl.precoGatilho(), Validade.FOK)),
                List.of(tp, new Perna(sl.id(), TipoOrdem.STOP, 2, null, sl.precoGatilho(), null)),
                List.of(tp, new Perna(sl.id(), TipoOrdem.STOP, 2, null, null, Validade.DIA)),
                List.of(sl, tp));
        for (var pernas : invalidos) {
            var c = new ComandoRisco(base.evento(), base.intencao(), base.conta(), base.ticker(), base.lado(),
                    base.precoBase(), pernas, null, SESSAO);
            assertFalse(c.oco(), pernas.toString());
            assertFalse(c.admissivel(), pernas.toString());
        }
        assertTrue(base.oco());
        var semSessao = new ComandoRisco(base.evento(), base.intencao(), base.conta(), base.ticker(), base.lado(),
                base.precoBase(), base.pernas(), null);
        assertTrue(semSessao.oco(), "o formato é OCO; a sessão é que falta");
        assertFalse(semSessao.admissivel());
        var simples = new ComandoRisco(base.evento(), base.intencao(), base.conta(), base.ticker(), base.lado(),
                base.precoBase(), List.of(tp), null, SESSAO);
        assertFalse(simples.oco());
        assertTrue(simples.admissivel(), "Limit DIA simples continua admissível");
    }

    @Test
    void guardasDeCoerenciaEspelhamOParserV3DoMe() {
        // Fronteiras: preço igual ao gatilho é incoerente nos dois lados; limite do Stop Limit igual ao gatilho é aceito.
        assertFalse(par("5", "CONTA-002", Lado.COMPRA, 2, "10.20", TipoOrdem.STOP, null, "10.20").admissivel());
        assertFalse(par("5", "CONTA-003", Lado.VENDA, 2, "9.80", TipoOrdem.STOP, null, "9.80").admissivel());
        assertFalse(par("5", "CONTA-003", Lado.VENDA, 2, "9.70", TipoOrdem.STOP, null, "9.80").admissivel(),
                "venda exige alvo acima do gatilho");
        assertFalse(par("5", "CONTA-003", Lado.VENDA, 2, "10.20", TipoOrdem.STOP_LIMIT, "9.90", "9.80").admissivel(),
                "Stop Limit de venda acima do gatilho");
        assertTrue(par("5", "CONTA-003", Lado.VENDA, 2, "10.20", TipoOrdem.STOP_LIMIT, "9.80", "9.80").admissivel());
        assertTrue(par("5", "CONTA-002", Lado.COMPRA, 2, "9.80", TipoOrdem.STOP_LIMIT, "10.20", "10.20").admissivel());
        assertFalse(par("5", "CONTA-002", Lado.COMPRA, 2, "9.80", TipoOrdem.STOP, "10.30", "10.20").admissivel(),
                "Stop simples não tem limite");
        assertFalse(par("5", "CONTA-002", Lado.COMPRA, 2, "9.80", TipoOrdem.STOP_LIMIT, null, "10.20").admissivel(),
                "Stop Limit exige limite");
        var base = par("5", "CONTA-002", Lado.COMPRA, 2, "9.80", TipoOrdem.STOP, null, "10.20");
        var tp = base.pernas().get(0);
        var sl = base.pernas().get(1);
        var quantidadeDiferente = new Perna(sl.id(), TipoOrdem.STOP, 3, null, sl.precoGatilho(), Validade.DIA);
        var mesmaOrdem = new Perna(tp.id(), TipoOrdem.STOP, 2, null, sl.precoGatilho(), Validade.DIA);
        for (var stop : List.of(quantidadeDiferente, mesmaOrdem)) {
            assertFalse(new ComandoRisco(base.evento(), base.intencao(), base.conta(), base.ticker(), base.lado(),
                    base.precoBase(), List.of(tp, stop), null, SESSAO).admissivel(), stop.toString());
        }
    }
}
