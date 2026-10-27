package br.prospero.me.livro;

import br.prospero.me.livro.dominio.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

import static br.prospero.me.livro.dominio.ResultadoCancelamentoManual.Situacao.*;
import static org.junit.jupiter.api.Assertions.*;

/** Contrato público RF10b1: sequência serial, sem reflexão, transporte ou reserva financeira. */
class CancelamentoManualTest {
    private static final LocalDate DIA = LocalDate.of(2026, 10, 27);
    private static final Map<String, ParametrosAtivo> ATIVOS = Map.of(
            "ALFA3", new ParametrosAtivo("ALFA3", d("0.01"), d("10.00"), BigDecimal.TEN),
            "BETA3", new ParametrosAtivo("BETA3", d("0.01"), d("10.00"), BigDecimal.TEN));
    private int id;

    private static BigDecimal d(String s) { return s == null ? null : new BigDecimal(s); }
    private static UUID uuid(String s) { return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8)); }
    private Admissao ordem(Lado lado, TipoOrdem tipo, long q, String preco, String gatilho) {
        int n = ++id;
        return new Admissao(uuid("ordem" + n), uuid("intencao" + n), "C" + lado, "ALFA3", lado, tipo,
                tipo == TipoOrdem.MARKET ? Validade.IOC : Validade.DIA, q, d(preco),
                lado == Lado.COMPRA ? d("100.00") : null, d(gatilho), DIA);
    }
    private Admissao limit(Lado lado, long q, String p) { return ordem(lado, TipoOrdem.LIMIT, q, p, null); }
    private static AlvoCancelamento alvo(Admissao a) {
        return new AlvoCancelamento(a.ordem(), a.intencao(), a.conta(), a.ticker(), a.sessao());
    }
    private static <T> List<T> tipos(List<Evento> es, Class<T> tipo) {
        return es.stream().filter(tipo::isInstance).map(tipo::cast).toList();
    }
    private static void aplicado(ResultadoCancelamentoManual r, Admissao a, long executada, long cancelada) {
        assertEquals(APLICADO, r.situacao());
        assertEquals(List.of(new SaldoCanceladoManual(a.ordem(), a.ticker(), executada, cancelada)), r.terminais());
    }
    private static void recusado(ResultadoCancelamentoManual r, ResultadoCancelamentoManual.Situacao s) {
        assertEquals(s, r.situacao());
        assertTrue(r.terminais().isEmpty());
    }
    private void negocio(MotorMatching m) {
        m.processar(limit(Lado.VENDA, 1, "10.00"));
        m.processar(limit(Lado.COMPRA, 1, "10.00"));
    }

    @Test void cancelaLimitCompraEVendaTotalEParcialSemNegocioPosterior() {
        for (Lado lado : Lado.values()) for (int parcial : new int[]{0, 2}) {
            MotorMatching m = new MotorMatching(ATIVOS);
            Admissao a = limit(lado, 5, "10.00");
            m.processar(a);
            Lado outro = lado == Lado.COMPRA ? Lado.VENDA : Lado.COMPRA;
            if (parcial > 0) assertEquals(1, tipos(m.processar(limit(outro, parcial, "10.00")), Evento.Negocio.class).size());
            aplicado(m.cancelarManual(alvo(a)), a, parcial, 5 - parcial);
            assertTrue(tipos(m.processar(limit(outro, 5, "10.00")), Evento.Negocio.class).isEmpty());
            assertTrue(m.encerrarSessao("ALFA3", DIA).stream().noneMatch(e -> e instanceof Evento.OrdemEncerrada f && f.ordem().equals(a.ordem())));
        }
    }

    @Test void retiraStopEStopLimitPendentesDosDoisLadosSemAcionamentoPosterior() {
        for (Lado lado : Lado.values()) for (TipoOrdem tipo : List.of(TipoOrdem.STOP, TipoOrdem.STOP_LIMIT)) {
            MotorMatching m = new MotorMatching(ATIVOS);
            Admissao s = ordem(lado, tipo, 3, tipo == TipoOrdem.STOP_LIMIT ? "10.00" : null, "10.00");
            m.processar(s);
            aplicado(m.cancelarManual(alvo(s)), s, 0, 3);
            m.processar(limit(Lado.VENDA, 1, "10.00"));
            assertTrue(tipos(m.processar(limit(Lado.COMPRA, 1, "10.00")), Evento.StopAcionado.class).isEmpty());
        }
    }

    @Test void stopLimitAcionadoParcialPreservaExecutadaCancelaSoSaldoNosDoisLados() {
        for (Lado lado : Lado.values()) {
            MotorMatching m = new MotorMatching(ATIVOS);
            negocio(m);
            m.processar(limit(lado == Lado.COMPRA ? Lado.VENDA : Lado.COMPRA, 2, "10.00"));
            Admissao s = ordem(lado, TipoOrdem.STOP_LIMIT, 5, "10.00", "10.00");
            assertEquals(1, tipos(m.processar(s), Evento.StopAcionado.class).size());
            aplicado(m.cancelarManual(alvo(s)), s, 2, 3);
        }
    }

    @Test void mercadoEStopAcionadoSemSaldoNaoGeramTerminalDeCancelamento() {
        MotorMatching m = new MotorMatching(ATIVOS);
        negocio(m);
        for (TipoOrdem tipo : List.of(TipoOrdem.MARKET, TipoOrdem.STOP)) {
            Admissao a = ordem(Lado.COMPRA, tipo, 2, null, tipo == TipoOrdem.STOP ? "10.00" : null);
            m.processar(a);
            recusado(m.cancelarManual(alvo(a)), SEM_SALDO_VIVO);
        }
    }

    @Test void todasIdentidadesDivergentesRecusamSemRetirarOrdem() {
        MotorMatching m = new MotorMatching(ATIVOS);
        Admissao a = limit(Lado.COMPRA, 3, "10.00");
        m.processar(a);
        for (AlvoCancelamento t : List.of(
                new AlvoCancelamento(a.ordem(), uuid("outra"), a.conta(), a.ticker(), DIA),
                new AlvoCancelamento(a.ordem(), a.intencao(), "ALHEIA", a.ticker(), DIA),
                new AlvoCancelamento(a.ordem(), a.intencao(), a.conta(), a.ticker(), DIA.plusDays(1)))) {
            recusado(m.cancelarManual(t), ALVO_DIVERGENTE);
        }
        recusado(m.cancelarManual(new AlvoCancelamento(a.ordem(), a.intencao(), a.conta(), "BETA3", DIA)), SEM_SALDO_VIVO);
        recusado(m.cancelarManual(new AlvoCancelamento(uuid("outra"), a.intencao(), a.conta(), a.ticker(), DIA)), SEM_SALDO_VIVO);
        aplicado(m.cancelarManual(alvo(a)), a, 0, 3);
    }

    @Test void pedidoAntesDaAdmissaoNaoConsomeIdNemAbreSessao() {
        MotorMatching m = new MotorMatching(ATIVOS);
        Admissao a = limit(Lado.COMPRA, 2, "10.00");
        recusado(m.cancelarManual(new AlvoCancelamento(a.ordem(), a.intencao(), a.conta(), a.ticker(), DIA.plusDays(1))), SEM_SALDO_VIVO);
        assertInstanceOf(Evento.OrdemAceita.class, m.processar(a).getFirst());
        aplicado(m.cancelarManual(alvo(a)), a, 0, 2);
        assertTrue(m.processar(a).isEmpty());
    }

    @Test void repeticaoNaoProduzSegundoFatoENaoRessuscitaOrdem() {
        MotorMatching m = new MotorMatching(ATIVOS);
        Admissao a = limit(Lado.VENDA, 3, "10.00");
        m.processar(a);
        aplicado(m.cancelarManual(alvo(a)), a, 0, 3);
        recusado(m.cancelarManual(alvo(a)), SEM_SALDO_VIVO);
        assertTrue(m.processar(a).isEmpty());
        assertTrue(m.encerrarSessao("ALFA3", DIA).isEmpty());
    }

    @Test void retiradaDoMeioPreservaFifoSequenciaENegocios() {
        MotorMatching m = new MotorMatching(ATIVOS);
        Admissao a = limit(Lado.VENDA, 1, "10.00"), b = limit(Lado.VENDA, 1, "10.00"), c = limit(Lado.VENDA, 1, "10.00");
        m.processar(a); m.processar(b); m.processar(c);
        aplicado(m.cancelarManual(alvo(b)), b, 0, 1);
        var es = m.processar(limit(Lado.COMPRA, 2, "10.00"));
        assertEquals(List.of(a.ordem(), c.ordem()), tipos(es, Evento.Negocio.class).stream().map(Evento.Negocio::ordemVenda).toList());
        assertEquals(List.of("ALFA3-2026-10-27-1", "ALFA3-2026-10-27-2"), tipos(es, Evento.Negocio.class).stream().map(Evento.Negocio::id).toList());
        assertEquals(4, tipos(es, Evento.OrdemAceita.class).getFirst().sequencia());
    }

    private AdmissaoOco oco(TipoOrdem tipo) {
        Admissao tp = limit(Lado.VENDA, 5, "11.00");
        Admissao base = ordem(Lado.VENDA, tipo, 5, tipo == TipoOrdem.STOP_LIMIT ? "9.00" : null, "9.00");
        Admissao sl = new Admissao(base.ordem(), tp.intencao(), tp.conta(), tp.ticker(), tp.lado(), base.tipo(), base.validade(), base.quantidade(), base.precoLimite(), base.precoMaximo(), base.precoGatilho(), DIA);
        return new AdmissaoOco(tp, sl);
    }

    @Test void ocoVivoRecusaAmbasPernasSemDissolverEContinuaReducaoAutomatica() {
        MotorMatching m = new MotorMatching(ATIVOS);
        AdmissaoOco p = oco(TipoOrdem.STOP_LIMIT);
        m.processarOco(p);
        recusado(m.cancelarManual(alvo(p.takeProfit())), ORIGEM_OCO);
        recusado(m.cancelarManual(alvo(p.stopLoss())), ORIGEM_OCO);
        var es = m.processar(limit(Lado.COMPRA, 2, "11.00"));
        assertEquals(List.of(new Evento.OrdemReduzida(p.stopLoss().ordem(), "ALFA3", 2, 3)), tipos(es, Evento.OrdemReduzida.class));
        assertEquals(2, m.encerrarSessao("ALFA3", DIA).size());
    }

    @Test void ocoDissolvidoConservaInelegibilidadeDoStopLimitSobrevivente() {
        MotorMatching m = new MotorMatching(ATIVOS);
        AdmissaoOco p = oco(TipoOrdem.STOP_LIMIT);
        m.processarOco(p);
        m.processar(limit(Lado.VENDA, 1, "9.00"));
        var es = m.processar(limit(Lado.COMPRA, 1, "9.00"));
        assertEquals(1, tipos(es, Evento.StopAcionado.class).size());
        recusado(m.cancelarManual(alvo(p.stopLoss())), ORIGEM_OCO);
        recusado(m.cancelarManual(alvo(p.takeProfit())), SEM_SALDO_VIVO);
        assertEquals(p.stopLoss().ordem(), tipos(m.processar(limit(Lado.COMPRA, 1, "9.00")), Evento.Negocio.class).getFirst().ordemVenda());
    }

    @Test void divergenciaPrecedeRecusaOcoSemMudarProtecao() {
        MotorMatching m = new MotorMatching(ATIVOS);
        AdmissaoOco p = oco(TipoOrdem.STOP);
        m.processarOco(p);
        Admissao tp = p.takeProfit();
        recusado(m.cancelarManual(new AlvoCancelamento(tp.ordem(), tp.intencao(), "ALHEIA", tp.ticker(), DIA)), ALVO_DIVERGENTE);
        recusado(m.cancelarManual(alvo(tp)), ORIGEM_OCO);
    }

    @Test void retiradaRecuperaVagaAtivaEPendenteSemAumentarLimites() {
        for (boolean pendente : new boolean[]{false, true}) {
            MotorMatching m = new MotorMatching(ATIVOS);
            Admissao primeira = null;
            int teto = pendente ? 500 : 1000;
            for (int n = 0; n < teto; n++) {
                Admissao a = pendente ? ordem(Lado.COMPRA, TipoOrdem.STOP, 1, null, "11.00") : limit(Lado.COMPRA, 1, "9.00");
                if (primeira == null) primeira = a;
                m.processar(a);
            }
            Admissao excedente = pendente ? ordem(Lado.COMPRA, TipoOrdem.STOP, 1, null, "11.00") : limit(Lado.COMPRA, 1, "9.00");
            assertEquals(1, tipos(m.processar(excedente), Evento.OrdemRejeitada.class).size());
            aplicado(m.cancelarManual(alvo(primeira)), primeira, 0, 1);
            Admissao nova = pendente ? ordem(Lado.COMPRA, TipoOrdem.STOP, 1, null, "11.00") : limit(Lado.COMPRA, 1, "9.00");
            assertInstanceOf(Evento.OrdemAceita.class, m.processar(nova).getFirst());
            assertEquals(teto, m.encerrarSessao("ALFA3", DIA).size());
        }
    }

    @Test void corteExecucaoECancelamentoNasDuasOrdens() {
        for (boolean antes : new boolean[]{true, false}) {
            MotorMatching m = new MotorMatching(ATIVOS);
            Admissao a = limit(Lado.VENDA, 2, "10.00");
            m.processar(a);
            if (antes) aplicado(m.cancelarManual(alvo(a)), a, 0, 2);
            var es = m.processar(limit(Lado.COMPRA, 2, "10.00"));
            assertEquals(antes ? 0 : 1, tipos(es, Evento.Negocio.class).size());
            if (!antes) recusado(m.cancelarManual(alvo(a)), SEM_SALDO_VIVO);
        }
    }

    @Test void corteAcionamentoECancelamentoNasDuasOrdens() {
        for (boolean antes : new boolean[]{true, false}) {
            MotorMatching m = new MotorMatching(ATIVOS);
            Admissao s = ordem(Lado.COMPRA, TipoOrdem.STOP_LIMIT, 3, "10.00", "10.00");
            m.processar(s);
            if (antes) aplicado(m.cancelarManual(alvo(s)), s, 0, 3);
            m.processar(limit(Lado.VENDA, 1, "10.00"));
            var es = m.processar(limit(Lado.COMPRA, 1, "10.00"));
            assertEquals(antes ? 0 : 1, tipos(es, Evento.StopAcionado.class).size());
            if (!antes) aplicado(m.cancelarManual(alvo(s)), s, 0, 3);
        }
    }

    @Test void corteFechamentoECancelamentoNasDuasOrdensSemReabrirSessao() {
        for (boolean antes : new boolean[]{true, false}) {
            MotorMatching m = new MotorMatching(ATIVOS);
            Admissao a = limit(Lado.VENDA, 2, "10.00");
            m.processar(a);
            if (antes) aplicado(m.cancelarManual(alvo(a)), a, 0, 2);
            assertEquals(antes ? 0 : 1, m.encerrarSessao("ALFA3", DIA).size());
            recusado(m.cancelarManual(alvo(a)), SEM_SALDO_VIVO);
            assertEquals(MotivoRejeicao.SESSAO_ENCERRADA, tipos(m.processar(limit(Lado.COMPRA, 1, "10.00")), Evento.OrdemRejeitada.class).getFirst().motivo());
        }
    }

    @Test void retiradaNaoResetaUltimoPrecoUsadoPeloTunelMarket() {
        MotorMatching m = new MotorMatching(ATIVOS);
        m.processar(limit(Lado.VENDA, 1, "10.50"));
        m.processar(limit(Lado.COMPRA, 1, "10.50"));
        Admissao retirada = limit(Lado.VENDA, 2, "10.60");
        m.processar(retirada);
        aplicado(m.cancelarManual(alvo(retirada)), retirada, 0, 2);
        Admissao passiva = limit(Lado.VENDA, 1, "11.40");
        m.processar(passiva);
        var es = m.processar(ordem(Lado.COMPRA, TipoOrdem.MARKET, 1, null, null));
        Evento.Negocio n = tipos(es, Evento.Negocio.class).getFirst();
        assertEquals(passiva.ordem(), n.ordemVenda());
        assertEquals(d("11.40"), n.preco());
        assertEquals("ALFA3-2026-10-27-2", n.id());
    }

    @Test void reproducaoDaMesmaSequenciaInternaGeraMesmosResultados() {
        MotorMatching a = new MotorMatching(ATIVOS), b = new MotorMatching(ATIVOS);
        List<Admissao> ordens = List.of(limit(Lado.COMPRA, 5, "9.00"), limit(Lado.VENDA, 2, "9.00"), ordem(Lado.VENDA, TipoOrdem.STOP_LIMIT, 3, "8.00", "8.00"));
        for (Admissao o : ordens) assertEquals(a.processar(o), b.processar(o));
        for (Admissao o : ordens) assertEquals(a.cancelarManual(alvo(o)), b.cancelarManual(alvo(o)));
        assertEquals(a.encerrarSessao("ALFA3", DIA), b.encerrarSessao("ALFA3", DIA));
    }
}
