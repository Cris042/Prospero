package br.prospero.fo.ordens;

import br.prospero.fo.ordens.dominio.*;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import br.prospero.fo.ordens.helper.ProjetarMatching;
import br.prospero.fo.comum.dominio.MensagemInvalidaException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RF05c.3b1: projeção pura das pernas OCO com fatos v3. */
class OcoProjecaoTest {
    private static final UUID GRUPO = UUID.fromString("e4b8a895-e56a-4430-925b-29236393db61");
    private static final UUID TP = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SL = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static Ordem perna(PapelOco papel, TipoOrdem tipo) {
        boolean tp = papel == PapelOco.TAKE_PROFIT;
        return new Ordem(tp ? TP : SL, "CONTA-003", "ALFA3", LadoOrdem.VENDA, tipo, Validade.DIA, 10,
                tp ? new BigDecimal("12.00") : tipo == TipoOrdem.STOP_LIMIT ? new BigDecimal("8.90") : null,
                tp ? null : new BigDecimal("9.00"), GRUPO, papel, GRUPO, EstadoOrdem.RESERVADA, LocalDate.of(2026, 10, 18),
                Instant.parse("2026-10-18T12:00:00Z"), null);
    }

    private static Ordem tp() { return perna(PapelOco.TAKE_PROFIT, TipoOrdem.LIMIT); }
    private static Ordem sl() { return perna(PapelOco.STOP_LOSS, TipoOrdem.STOP_LIMIT); }

    private static EventoMatching evento(int versao, String tipo, UUID ordem, String campos) {
        String s = """
                {"id":"80ef3249-7815-4560-a260-63ecf28bd581","tipo":"%s","versao":%d,"origem":"matching-engine",
                "correlacao":"%s","causacao":"ed42a1e3-9ef9-4ba5-87b4-a0bcf224a316",
                "ocorridoEm":"2026-10-18T12:00:00Z","ticker":"ALFA3","dataSessao":"2026-10-18","traceparent":null,
                "dados":{"ordem":"%s","intencao":"%s","conta":"CONTA-003",%s}}
                """.formatted(tipo, versao, GRUPO, ordem, GRUPO, campos);
        return EnvelopeMatching.evento(s.getBytes(StandardCharsets.UTF_8), "ALFA3");
    }

    private static EventoMatching v3(String tipo, UUID ordem, String campos) { return evento(3, tipo, ordem, campos); }

    private static Ordem aplicar(Ordem o, EventoMatching e) {
        return ProjetarMatching.aplicar(o, e, new EventoMatching.Alvo(o.id(), GRUPO, "CONTA-003"), new BigDecimal("0.01"), 1);
    }

    private static EventoMatching negocio(UUID venda, long quantidade, String preco) {
        return new EventoMatching(UUID.randomUUID(), UUID.randomUUID(), "ALFA3", LocalDate.of(2026, 10, 18), "h",
                new EventoMatching.Negocio("ALFA3-2026-10-18-1", new BigDecimal(preco), quantidade,
                        new EventoMatching.Alvo(UUID.randomUUID(), UUID.randomUUID(), "CONTA-002"),
                        new EventoMatching.Alvo(venda, GRUPO, "CONTA-003"), LadoOrdem.COMPRA), UUID.randomUUID(), 3);
    }

    @Test
    void aceiteAbreTpEDeixaSlAguardandoGatilhoSoEmV3() {
        assertEquals(EstadoOrdem.ABERTA, aplicar(tp(), v3("ORDEM_ACEITA", TP, "\"sequencia\":1")).estado());
        assertEquals(EstadoOrdem.AGUARDANDO_GATILHO, aplicar(sl(), v3("ORDEM_ACEITA", SL, "\"sequencia\":2")).estado());
        assertThrows(MatchingIncoerenteException.class, () -> aplicar(tp(), evento(2, "ORDEM_ACEITA", TP, "\"sequencia\":1")));
    }

    @Test
    void reducaoMudaSoQuantidadeVigenteDoSlEConservaProgresso() {
        Ordem s = aplicar(sl(), v3("ORDEM_ACEITA", SL, "\"sequencia\":2"));
        s = aplicar(s, v3("ORDEM_REDUZIDA", SL, "\"reduzida\":4,\"restante\":6"));
        assertEquals(4, s.quantidadeReduzida());
        assertEquals(EstadoOrdem.AGUARDANDO_GATILHO, s.estado());
        Ordem reduzido = s;
        assertThrows(MatchingIncoerenteException.class,
                () -> aplicar(reduzido, v3("ORDEM_REDUZIDA", SL, "\"reduzida\":1,\"restante\":6")), "restante diverge");
        Ordem t = aplicar(tp(), v3("ORDEM_ACEITA", TP, "\"sequencia\":1"));
        assertThrows(MatchingIncoerenteException.class,
                () -> aplicar(t, v3("ORDEM_REDUZIDA", TP, "\"reduzida\":1,\"restante\":9")), "TP nunca é reduzido");
        s = aplicar(s, v3("STOP_ACIONADO", SL, "\"tipoExecutavel\":\"LIMIT\",\"sequencia\":3"));
        s = aplicar(s, v3("OFERTA_NO_LIVRO", SL, "\"lado\":\"VENDA\",\"preco\":\"8.90\",\"restante\":6"));
        assertEquals(EstadoOrdem.ABERTA, s.estado());
        Ordem aberto = s;
        assertThrows(MatchingIncoerenteException.class, () -> aplicar(aberto, negocio(SL, 7, "8.90")), "acima da vigente");
        s = aplicar(s, negocio(SL, 6, "8.90"));
        assertEquals(EstadoOrdem.EXECUTADA, s.estado());
        assertEquals(6, s.quantidadeExecutada());
    }

    @Test
    void cancelamentoOcoEncerraCadaPernaComSaldoProprio() {
        Ordem t = aplicar(tp(), v3("ORDEM_ACEITA", TP, "\"sequencia\":1"));
        t = aplicar(t, negocio(TP, 4, "12.00"));
        t = aplicar(t, v3("ORDEM_ENCERRADA", TP, "\"motivo\":\"CANCELADA_OCO\",\"executada\":4,\"cancelada\":6"));
        assertEquals(EstadoOrdem.CANCELADA, t.estado());
        assertEquals("CANCELADA_OCO", t.motivoEncerramento());
        Ordem s = aplicar(sl(), v3("ORDEM_ACEITA", SL, "\"sequencia\":2"));
        s = aplicar(s, v3("ORDEM_REDUZIDA", SL, "\"reduzida\":4,\"restante\":6"));
        s = aplicar(s, v3("ORDEM_ENCERRADA", SL, "\"motivo\":\"CANCELADA_OCO\",\"executada\":0,\"cancelada\":6"));
        assertEquals(EstadoOrdem.CANCELADA, s.estado());
        assertEquals(6, s.quantidadeCancelada());
        Ordem simples = new Ordem(TP, "CONTA-003", "ALFA3", LadoOrdem.VENDA, TipoOrdem.LIMIT, Validade.DIA, 10,
                new BigDecimal("12.00"), null, null, null, GRUPO, EstadoOrdem.ABERTA, LocalDate.of(2026, 10, 18),
                Instant.parse("2026-10-18T12:00:00Z"), null);
        assertThrows(MatchingIncoerenteException.class, () -> aplicar(simples,
                v3("ORDEM_ENCERRADA", TP, "\"motivo\":\"CANCELADA_OCO\",\"executada\":0,\"cancelada\":10")));
    }

    @Test
    void rejeicaoDoParPorLimiteDePendentesAlcancaAsDuasPernas() {
        String campos = "\"motivo\":\"LIMITE_STOPS_PENDENTES\"";
        assertEquals(EstadoOrdem.REJEITADA, aplicar(tp(), v3("ORDEM_REJEITADA", TP, campos)).estado());
        assertEquals(EstadoOrdem.REJEITADA, aplicar(sl(), v3("ORDEM_REJEITADA", SL, campos)).estado());
    }

    @Test
    void fronteiraRecusaFatosOcoForaDaV3() {
        assertThrows(MensagemInvalidaException.class, () -> evento(2, "ORDEM_REDUZIDA", SL, "\"reduzida\":4,\"restante\":6"));
        assertThrows(MensagemInvalidaException.class,
                () -> evento(2, "ORDEM_ENCERRADA", SL, "\"motivo\":\"CANCELADA_OCO\",\"executada\":0,\"cancelada\":6"));
        assertThrows(MensagemInvalidaException.class, () -> v3("ORDEM_REDUZIDA", SL, "\"reduzida\":0,\"restante\":6"));
        assertEquals(3, v3("ORDEM_ACEITA", TP, "\"sequencia\":1").versao());
    }
}
