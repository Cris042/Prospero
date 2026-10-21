package br.prospero.me.livro;

import br.prospero.me.livro.dominio.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** RF05c.3a: par OCO no núcleo puro, com as decisões do ADR-013 de 2026-10-21. */
class OcoMatchingTest {
    private static final LocalDate DIA = LocalDate.of(2026, 10, 21);
    private static final Map<String, ParametrosAtivo> ATIVOS = Map.of(
            "ALFA3", new ParametrosAtivo("ALFA3", d("0.01"), d("10.00"), new BigDecimal("50")),
            "BETA3", new ParametrosAtivo("BETA3", d("0.01"), d("10.00"), BigDecimal.TEN));
    private int id;

    private static BigDecimal d(String valor) { return valor == null ? null : new BigDecimal(valor); }
    private static UUID uuid(String texto) { return UUID.nameUUIDFromBytes(texto.getBytes(StandardCharsets.UTF_8)); }

    private Admissao ordem(String conta, Lado lado, TipoOrdem tipo, long qtd, String limite, String gatilho) {
        int n = ++id;
        return new Admissao(uuid("ordem" + n), uuid("intencao" + n), conta, "ALFA3", lado, tipo, Validade.DIA, qtd,
                d(limite), lado == Lado.COMPRA ? d("100.00") : null, d(gatilho), DIA);
    }
    private Admissao limit(Lado lado, long qtd, String preco) {
        return ordem("C" + lado, lado, TipoOrdem.LIMIT, qtd, preco, null);
    }
    private AdmissaoOco oco(Lado lado, long qtd, String alvo, TipoOrdem tipoStop, String limiteStop, String gatilho) {
        return oco("INVESTIDOR", lado, qtd, alvo, tipoStop, limiteStop, gatilho, lado == Lado.COMPRA ? "100.00" : null);
    }
    private AdmissaoOco oco(String conta, Lado lado, long qtd, String alvo, TipoOrdem tipoStop, String limiteStop,
                            String gatilho, String maximo) {
        int n = ++id;
        UUID intencao = uuid("par" + n);
        return new AdmissaoOco(
                new Admissao(uuid("tp" + n), intencao, conta, "ALFA3", lado, TipoOrdem.LIMIT, Validade.DIA, qtd,
                        d(alvo), d(maximo), null, DIA),
                new Admissao(uuid("sl" + n), intencao, conta, "ALFA3", lado, tipoStop, Validade.DIA, qtd,
                        d(limiteStop), d(maximo), d(gatilho), DIA));
    }
    private static <T> List<T> tipos(List<Evento> eventos, Class<T> tipo) {
        return eventos.stream().filter(tipo::isInstance).map(tipo::cast).toList();
    }
    private static Evento.OrdemEncerrada fim(List<Evento> eventos, Admissao a) {
        List<Evento.OrdemEncerrada> finais = tipos(eventos, Evento.OrdemEncerrada.class).stream()
                .filter(e -> e.ordem().equals(a.ordem())).toList();
        assertEquals(1, finais.size(), "um terminal por perna");
        return finais.getFirst();
    }
    private static boolean semTerminal(List<Evento> eventos, Admissao a) {
        return tipos(eventos, Evento.OrdemEncerrada.class).stream().noneMatch(e -> e.ordem().equals(a.ordem()));
    }
    private void negocio(MotorMatching motor, String preco) {
        motor.processar(limit(Lado.VENDA, 1, preco));
        motor.processar(limit(Lado.COMPRA, 1, preco));
    }

    @Test
    void parAceitoRepousaTakeProfitEGuardaStopLossComSequenciasConsecutivas() {
        MotorMatching m = new MotorMatching(ATIVOS);
        AdmissaoOco par = oco(Lado.VENDA, 10, "12.00", TipoOrdem.STOP, null, "9.00");
        assertEquals(List.of(
                new Evento.OrdemAceita(par.takeProfit().ordem(), "ALFA3", 1),
                new Evento.OrdemAceita(par.stopLoss().ordem(), "ALFA3", 2),
                new Evento.OfertaNoLivro(par.takeProfit().ordem(), "ALFA3", Lado.VENDA, d("12.00"), 10),
                new Evento.StopPendente(par.stopLoss().ordem(), "ALFA3", d("9.00"))), m.processarOco(par));
        assertTrue(m.processarOco(par).isEmpty(), "reentrega do par é inócua");
    }

    @Test
    void parcialDoTakeProfitReduzStopLossEExecucaoTotalCancelaSaldo() {
        MotorMatching m = new MotorMatching(ATIVOS);
        AdmissaoOco par = oco(Lado.VENDA, 10, "12.00", TipoOrdem.STOP, null, "9.00");
        m.processarOco(par);
        List<Evento> parcial = m.processar(limit(Lado.COMPRA, 4, "12.00"));
        assertEquals(List.of(new Evento.OrdemReduzida(par.stopLoss().ordem(), "ALFA3", 4, 6)),
                tipos(parcial, Evento.OrdemReduzida.class));
        assertTrue(semTerminal(parcial, par.takeProfit()) && semTerminal(parcial, par.stopLoss()));
        List<Evento> total = m.processar(limit(Lado.COMPRA, 6, "12.00"));
        assertTrue(tipos(total, Evento.OrdemReduzida.class).isEmpty());
        assertEquals(new Evento.OrdemEncerrada(par.takeProfit().ordem(), "ALFA3", MotivoEncerramento.EXECUTADA, 10, 0),
                fim(total, par.takeProfit()));
        assertEquals(new Evento.OrdemEncerrada(par.stopLoss().ordem(), "ALFA3", MotivoEncerramento.CANCELADA_OCO, 0, 6),
                fim(total, par.stopLoss()));
        // Stop cancelado deixou a pendência: um negócio no gatilho não o aciona.
        List<Evento> depois = new ArrayList<>();
        depois.addAll(m.processar(limit(Lado.VENDA, 1, "9.00")));
        depois.addAll(m.processar(limit(Lado.COMPRA, 1, "9.00")));
        assertTrue(tipos(depois, Evento.StopAcionado.class).isEmpty());
    }

    @Test
    void acionamentoDoStopLossCancelaTakeProfitNaCapturaERetiraDoLivro() {
        MotorMatching m = new MotorMatching(ATIVOS);
        AdmissaoOco par = oco(Lado.VENDA, 10, "12.00", TipoOrdem.STOP_LIMIT, "8.90", "9.00");
        m.processarOco(par);
        m.processar(limit(Lado.COMPRA, 4, "12.00"));
        m.processar(limit(Lado.COMPRA, 2, "8.95"));
        m.processar(limit(Lado.VENDA, 1, "9.00"));
        List<Evento> eventos = m.processar(limit(Lado.COMPRA, 1, "9.00"));
        Evento.OrdemEncerrada tp = fim(eventos, par.takeProfit());
        assertEquals(new Evento.OrdemEncerrada(par.takeProfit().ordem(), "ALFA3", MotivoEncerramento.CANCELADA_OCO, 4, 6),
                tp);
        Evento.StopAcionado acionado = tipos(eventos, Evento.StopAcionado.class).getFirst();
        assertTrue(eventos.indexOf(tp) < eventos.indexOf(acionado), "TP cancelado na captura, antes do dreno");
        Evento.Negocio n = tipos(eventos, Evento.Negocio.class).getLast();
        assertEquals(par.stopLoss().ordem(), n.ordemVenda());
        assertEquals(2, n.quantidade());
        assertEquals(new Evento.OfertaNoLivro(par.stopLoss().ordem(), "ALFA3", Lado.VENDA, d("8.90"), 4),
                tipos(eventos, Evento.OfertaNoLivro.class).getLast(), "SL convertido com o saldo reduzido");
        // O TP saiu do livro: uma compra a 12,00 não o encontra, mas negocia com o SL repousado a 8,90.
        Evento.Negocio seguinte = tipos(m.processar(limit(Lado.COMPRA, 1, "12.00")), Evento.Negocio.class).getFirst();
        assertEquals(par.stopLoss().ordem(), seguinte.ordemVenda());
        assertEquals(d("8.90"), seguinte.preco());
    }

    @Test
    void takeProfitPrimeiroNaAdmissaoReavaliaStopLossReduzidoDepoisDoTakeProfit() {
        MotorMatching m = new MotorMatching(ATIVOS);
        negocio(m, "9.00");
        m.processar(limit(Lado.COMPRA, 3, "12.00"));
        AdmissaoOco par = oco(Lado.VENDA, 10, "12.00", TipoOrdem.STOP, null, "9.00");
        List<Evento> eventos = m.processarOco(par);
        Evento.Negocio n = tipos(eventos, Evento.Negocio.class).getFirst();
        assertEquals(par.takeProfit().ordem(), n.ordemVenda());
        assertEquals(List.of(new Evento.OrdemReduzida(par.stopLoss().ordem(), "ALFA3", 3, 7)),
                tipos(eventos, Evento.OrdemReduzida.class));
        // O último negócio passou a 12,00: o SL reduzido não está mais satisfeito e fica pendente.
        assertEquals(List.of(new Evento.StopPendente(par.stopLoss().ordem(), "ALFA3", d("9.00"))),
                tipos(eventos, Evento.StopPendente.class));
        assertEquals(new Evento.OfertaNoLivro(par.takeProfit().ordem(), "ALFA3", Lado.VENDA, d("12.00"), 7),
                tipos(eventos, Evento.OfertaNoLivro.class).getFirst());
    }

    @Test
    void takeProfitQueRepousaComStopLossSatisfeitoECanceladoPeloAcionamento() {
        MotorMatching m = new MotorMatching(ATIVOS);
        negocio(m, "9.00");
        m.processar(limit(Lado.COMPRA, 4, "8.80"));
        AdmissaoOco par = oco(Lado.VENDA, 10, "12.00", TipoOrdem.STOP, null, "9.00");
        List<Evento> eventos = m.processarOco(par);
        assertEquals(new Evento.OrdemEncerrada(par.takeProfit().ordem(), "ALFA3", MotivoEncerramento.CANCELADA_OCO, 0, 10),
                fim(eventos, par.takeProfit()));
        assertTrue(tipos(eventos, Evento.StopPendente.class).isEmpty());
        assertEquals(TipoOrdem.MARKET, tipos(eventos, Evento.StopAcionado.class).getFirst().tipoExecutavel());
        assertEquals(new Evento.OrdemEncerrada(par.stopLoss().ordem(), "ALFA3", MotivoEncerramento.SEM_LIQUIDEZ, 4, 6),
                fim(eventos, par.stopLoss()));
        assertTrue(eventos.indexOf(fim(eventos, par.takeProfit()))
                < eventos.indexOf(tipos(eventos, Evento.StopAcionado.class).getFirst()));
    }

    @Test
    void takeProfitIntegralNaAdmissaoCancelaStopLossSemPendencia() {
        MotorMatching m = new MotorMatching(ATIVOS);
        m.processar(limit(Lado.COMPRA, 4, "12.10"));
        m.processar(limit(Lado.COMPRA, 6, "12.00"));
        AdmissaoOco par = oco(Lado.VENDA, 10, "12.00", TipoOrdem.STOP, null, "9.00");
        List<Evento> eventos = m.processarOco(par);
        assertEquals(List.of(new Evento.OrdemReduzida(par.stopLoss().ordem(), "ALFA3", 4, 6)),
                tipos(eventos, Evento.OrdemReduzida.class));
        assertEquals(new Evento.OrdemEncerrada(par.takeProfit().ordem(), "ALFA3", MotivoEncerramento.EXECUTADA, 10, 0),
                fim(eventos, par.takeProfit()));
        assertEquals(new Evento.OrdemEncerrada(par.stopLoss().ordem(), "ALFA3", MotivoEncerramento.CANCELADA_OCO, 0, 6),
                fim(eventos, par.stopLoss()));
        assertTrue(tipos(eventos, Evento.StopPendente.class).isEmpty());
        assertTrue(tipos(eventos, Evento.OfertaNoLivro.class).isEmpty());
    }

    @Test
    void parDeCompraEspelhado() {
        MotorMatching m = new MotorMatching(ATIVOS);
        AdmissaoOco par = oco(Lado.COMPRA, 5, "8.00", TipoOrdem.STOP, null, "11.00");
        m.processarOco(par);
        m.processar(limit(Lado.VENDA, 2, "8.00"));
        m.processar(limit(Lado.VENDA, 9, "11.50"));
        m.processar(limit(Lado.VENDA, 1, "11.00"));
        List<Evento> eventos = m.processar(limit(Lado.COMPRA, 1, "11.00"));
        assertEquals(new Evento.OrdemEncerrada(par.takeProfit().ordem(), "ALFA3", MotivoEncerramento.CANCELADA_OCO, 2, 3),
                fim(eventos, par.takeProfit()));
        assertEquals(new Evento.OrdemEncerrada(par.stopLoss().ordem(), "ALFA3", MotivoEncerramento.EXECUTADA, 3, 0),
                fim(eventos, par.stopLoss()));
    }

    @Test
    void parIncoerenteERejeitadoInteiroSemTocarOLivro() {
        List<AdmissaoOco> invalidos = new ArrayList<>();
        invalidos.add(oco(Lado.VENDA, 10, "9.00", TipoOrdem.STOP, null, "9.00"));
        invalidos.add(oco(Lado.VENDA, 10, "8.00", TipoOrdem.STOP, null, "9.00"));
        invalidos.add(oco(Lado.COMPRA, 10, "11.00", TipoOrdem.STOP, null, "11.00"));
        invalidos.add(oco(Lado.VENDA, 10, "12.00", TipoOrdem.LIMIT, "9.00", null));
        invalidos.add(oco(Lado.COMPRA, 10, "8.00", TipoOrdem.STOP_LIMIT, "10.90", "11.00"));
        AdmissaoOco base = oco(Lado.VENDA, 10, "12.00", TipoOrdem.STOP, null, "9.00");
        Admissao tp = base.takeProfit(), sl = base.stopLoss();
        invalidos.add(new AdmissaoOco(tp, new Admissao(uuid("x1"), sl.intencao(), sl.conta(), sl.ticker(), sl.lado(),
                sl.tipo(), sl.validade(), 9, null, null, sl.precoGatilho(), DIA)));
        invalidos.add(new AdmissaoOco(tp, new Admissao(uuid("x2"), sl.intencao(), "OUTRA", sl.ticker(), sl.lado(),
                sl.tipo(), sl.validade(), 10, null, null, sl.precoGatilho(), DIA)));
        invalidos.add(new AdmissaoOco(tp, new Admissao(uuid("x3"), uuid("outra"), sl.conta(), sl.ticker(), sl.lado(),
                sl.tipo(), sl.validade(), 10, null, null, sl.precoGatilho(), DIA)));
        invalidos.add(new AdmissaoOco(tp, new Admissao(uuid("x4"), sl.intencao(), sl.conta(), "BETA3", sl.lado(),
                sl.tipo(), sl.validade(), 10, null, null, sl.precoGatilho(), DIA)));
        invalidos.add(new AdmissaoOco(new Admissao(uuid("x5"), tp.intencao(), tp.conta(), tp.ticker(), tp.lado(),
                tp.tipo(), Validade.IOC, 10, tp.precoLimite(), null, null, DIA), sl));
        invalidos.add(new AdmissaoOco(tp, new Admissao(tp.ordem(), sl.intencao(), sl.conta(), sl.ticker(), sl.lado(),
                sl.tipo(), sl.validade(), 10, null, null, sl.precoGatilho(), DIA)));
        AdmissaoOco compra = oco(Lado.COMPRA, 10, "8.00", TipoOrdem.STOP, null, "11.00");
        Admissao csl = compra.stopLoss();
        invalidos.add(new AdmissaoOco(compra.takeProfit(), new Admissao(uuid("x6"), csl.intencao(), csl.conta(),
                csl.ticker(), csl.lado(), csl.tipo(), csl.validade(), 10, null, d("99.00"), csl.precoGatilho(), DIA)));
        for (AdmissaoOco par : invalidos) {
            MotorMatching m = new MotorMatching(ATIVOS);
            List<Evento> eventos = m.processarOco(par);
            List<Evento.OrdemRejeitada> rejeicoes = tipos(eventos, Evento.OrdemRejeitada.class);
            assertEquals(eventos.size(), rejeicoes.size(), "nenhum aceite parcial: " + eventos);
            assertTrue(rejeicoes.stream().allMatch(r -> r.motivo() == MotivoRejeicao.COMBINACAO_INVALIDA), eventos::toString);
            Admissao contraparte = limit(par.takeProfit().lado().oposto(), 10, par.takeProfit().precoLimite().toPlainString());
            assertTrue(tipos(m.processar(contraparte), Evento.Negocio.class).isEmpty(), "livro intocado");
        }
    }

    @Test
    void parOcupaDuasVagasAtivasEUmaPendenteRejeitandoAsDuasPernas() {
        MotorMatching m = new MotorMatching(ATIVOS);
        for (int i = 0; i < 998; i++) {
            m.processar(ordem("INVESTIDOR", Lado.VENDA, TipoOrdem.LIMIT, 1, "15.00", null));
        }
        AdmissaoOco cabe = oco(Lado.VENDA, 1, "12.00", TipoOrdem.STOP, null, "9.00");
        assertFalse(tipos(m.processarOco(cabe), Evento.OrdemAceita.class).isEmpty());
        MotorMatching n = new MotorMatching(ATIVOS);
        for (int i = 0; i < 999; i++) {
            n.processar(ordem("INVESTIDOR", Lado.VENDA, TipoOrdem.LIMIT, 1, "15.00", null));
        }
        AdmissaoOco excede = oco(Lado.VENDA, 1, "12.00", TipoOrdem.STOP, null, "9.00");
        assertEquals(List.of(
                new Evento.OrdemRejeitada(excede.takeProfit().ordem(), "ALFA3", MotivoRejeicao.LIMITE_ORDENS_ATIVAS),
                new Evento.OrdemRejeitada(excede.stopLoss().ordem(), "ALFA3", MotivoRejeicao.LIMITE_ORDENS_ATIVAS)),
                n.processarOco(excede));

        MotorMatching p = new MotorMatching(ATIVOS);
        for (int i = 0; i < 499; i++) {
            p.processar(ordem("C" + (i % 400), Lado.VENDA, TipoOrdem.STOP, 1, null, "5.00"));
        }
        assertFalse(tipos(p.processarOco(oco(Lado.VENDA, 1, "12.00", TipoOrdem.STOP, null, "9.00")),
                Evento.StopPendente.class).isEmpty());
        AdmissaoOco semVaga = oco(Lado.VENDA, 1, "12.00", TipoOrdem.STOP, null, "9.00");
        assertEquals(List.of(
                new Evento.OrdemRejeitada(semVaga.takeProfit().ordem(), "ALFA3", MotivoRejeicao.LIMITE_STOPS_PENDENTES),
                new Evento.OrdemRejeitada(semVaga.stopLoss().ordem(), "ALFA3", MotivoRejeicao.LIMITE_STOPS_PENDENTES)),
                p.processarOco(semVaga));
    }

    @Test
    void stopLossAlemDaCascataEncerraTakeProfitTambem() {
        MotorMatching m = new MotorMatching(ATIVOS);
        for (int i = 0; i < MotorMatching.MAX_ATIVACOES_POR_COMANDO; i++) {
            m.processar(ordem("S" + i, Lado.VENDA, TipoOrdem.STOP, 1, null, "9.00"));
        }
        AdmissaoOco par = oco(Lado.VENDA, 10, "12.00", TipoOrdem.STOP, null, "9.00");
        m.processarOco(par);
        m.processar(limit(Lado.VENDA, 1, "9.00"));
        List<Evento> eventos = m.processar(limit(Lado.COMPRA, 1, "9.00"));
        assertEquals(new Evento.OrdemEncerrada(par.stopLoss().ordem(), "ALFA3", MotivoEncerramento.LIMITE_CASCATA, 0, 10),
                fim(eventos, par.stopLoss()));
        assertEquals(new Evento.OrdemEncerrada(par.takeProfit().ordem(), "ALFA3", MotivoEncerramento.CANCELADA_OCO, 0, 10),
                fim(eventos, par.takeProfit()));
        assertTrue(tipos(m.processar(limit(Lado.COMPRA, 1, "12.00")), Evento.Negocio.class).isEmpty());
    }

    @Test
    void mesmaReservaComEscalaDiferenteNaoTornaParIncoerente() {
        MotorMatching m = new MotorMatching(ATIVOS);
        AdmissaoOco par = oco("INVESTIDOR", Lado.COMPRA, 5, "8.00", TipoOrdem.STOP, null, "11.00", "12.00");
        Admissao sl = par.stopLoss();
        List<Evento> eventos = m.processarOco(new AdmissaoOco(par.takeProfit(), new Admissao(sl.ordem(), sl.intencao(),
                sl.conta(), sl.ticker(), sl.lado(), sl.tipo(), sl.validade(), sl.quantidade(), null, d("12.0"),
                sl.precoGatilho(), DIA)));
        assertTrue(tipos(eventos, Evento.OrdemRejeitada.class).isEmpty(), eventos::toString);
    }

    @Test
    void reentregaComUmaPernaJaVistaRejeitaSoAInedita() {
        MotorMatching m = new MotorMatching(ATIVOS);
        AdmissaoOco par = oco(Lado.VENDA, 10, "12.00", TipoOrdem.STOP, null, "9.00");
        m.processar(par.takeProfit());
        assertEquals(List.of(new Evento.OrdemRejeitada(par.stopLoss().ordem(), "ALFA3", MotivoRejeicao.COMBINACAO_INVALIDA)),
                m.processarOco(par));
        assertTrue(m.processarOco(par).isEmpty());
        assertTrue(m.processar(par.stopLoss()).isEmpty());
    }

    @Test
    void fechamentoExpiraPernasVivasEmChegadaConservandoReducao() {
        MotorMatching m = new MotorMatching(ATIVOS);
        AdmissaoOco par = oco(Lado.VENDA, 10, "12.00", TipoOrdem.STOP, null, "9.00");
        m.processarOco(par);
        m.processar(limit(Lado.COMPRA, 4, "12.00"));
        assertEquals(List.of(
                new Evento.OrdemEncerrada(par.takeProfit().ordem(), "ALFA3", MotivoEncerramento.EXPIRADA_DIA, 4, 6),
                new Evento.OrdemEncerrada(par.stopLoss().ordem(), "ALFA3", MotivoEncerramento.EXPIRADA_DIA, 0, 6)),
                m.encerrarSessao("ALFA3", DIA));
    }

    @Test
    void contagemDeAtivasVoltaAZeroAposPar() {
        MotorMatching m = new MotorMatching(ATIVOS);
        AdmissaoOco par = oco(Lado.VENDA, 2, "12.00", TipoOrdem.STOP, null, "9.00");
        m.processarOco(par);
        m.processar(limit(Lado.COMPRA, 2, "12.00"));
        for (int i = 0; i < MotorMatching.MAX_ORDENS_ATIVAS_POR_CONTA; i++) {
            List<Evento> eventos = m.processar(ordem("INVESTIDOR", Lado.VENDA, TipoOrdem.LIMIT, 1, "15.00", null));
            assertTrue(tipos(eventos, Evento.OrdemRejeitada.class).isEmpty(), "vaga " + i);
        }
    }
}
