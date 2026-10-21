package br.prospero.me.livro;

import br.prospero.me.livro.dominio.Admissao;
import br.prospero.me.livro.dominio.Evento;
import br.prospero.me.livro.dominio.Lado;
import br.prospero.me.livro.dominio.MotivoEncerramento;
import br.prospero.me.livro.dominio.MotorMatching;
import br.prospero.me.livro.dominio.ParametrosAtivo;
import br.prospero.me.livro.dominio.TipoOrdem;
import br.prospero.me.livro.dominio.Validade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RF05c.1a: fechamento explícito, sem relógio ou integração financeira. */
class EncerramentoSessaoTest {
    private static final LocalDate DIA = LocalDate.of(2026, 10, 19);
    private static final Map<String, ParametrosAtivo> ATIVOS = Map.of(
            "ALFA3", new ParametrosAtivo("ALFA3", new BigDecimal("0.01"), BigDecimal.TEN, BigDecimal.TEN),
            "BETA3", new ParametrosAtivo("BETA3", new BigDecimal("0.01"), BigDecimal.TEN, BigDecimal.TEN));

    private static Admissao ordem(String ticker, Lado lado, long quantidade, String preco, LocalDate dia) {
        BigDecimal limite = new BigDecimal(preco);
        return new Admissao(UUID.randomUUID(), UUID.randomUUID(), "CONTA-002", ticker, lado, TipoOrdem.LIMIT,
                Validade.DIA, quantidade, limite, lado == Lado.COMPRA ? limite : null, dia);
    }

    static List<Evento> fechar(MotorMatching motor, String ticker, LocalDate dia) {
        return motor.encerrarSessao(ticker, dia);
    }

    private static void rejeitada(List<Evento> eventos, String motivo) {
        assertEquals(1, eventos.size());
        assertEquals(motivo, assertInstanceOf(Evento.OrdemRejeitada.class, eventos.getFirst()).motivo().name());
    }

    @Test
    void novaDataSemFechamentoERecusadaSemNegociarOuAlterarSessao() {
        MotorMatching motor = new MotorMatching(ATIVOS);
        Admissao venda = ordem("ALFA3", Lado.VENDA, 10, "10.00", DIA);
        motor.processar(venda);
        rejeitada(motor.processar(ordem("ALFA3", Lado.COMPRA, 10, "10.00", DIA.plusDays(1))),
                "SESSAO_NAO_ENCERRADA");
        List<Evento> compra = motor.processar(ordem("ALFA3", Lado.COMPRA, 10, "10.00", DIA));
        assertEquals(venda.ordem(), compra.stream().filter(Evento.Negocio.class::isInstance)
                .map(Evento.Negocio.class::cast).findFirst().orElseThrow().ordemVenda());
    }

    @Test
    void expiraSaldosEmSequenciaGlobalDeChegadaComQuantidadesExatas() {
        MotorMatching motor = new MotorMatching(ATIVOS);
        Admissao venda = ordem("ALFA3", Lado.VENDA, 10, "10.50", DIA);
        Admissao compra = ordem("ALFA3", Lado.COMPRA, 7, "9.50", DIA);
        Admissao melhorVenda = ordem("ALFA3", Lado.VENDA, 3, "10.40", DIA);
        motor.processar(venda);
        motor.processar(compra);
        motor.processar(melhorVenda);
        motor.processar(ordem("ALFA3", Lado.COMPRA, 5, "10.50", DIA));
        List<Evento> fim = fechar(motor, "ALFA3", DIA);
        assertEquals(List.of(venda.ordem(), compra.ordem()), fim.stream()
                .map(e -> assertInstanceOf(Evento.OrdemEncerrada.class, e).ordem()).toList());
        Evento.OrdemEncerrada parcial = (Evento.OrdemEncerrada) fim.getFirst();
        assertEquals("EXPIRADA_DIA", parcial.motivo().name());
        assertEquals(2, parcial.executada());
        assertEquals(8, parcial.cancelada());
        Evento.OrdemEncerrada intacta = (Evento.OrdemEncerrada) fim.getLast();
        assertEquals("EXPIRADA_DIA", intacta.motivo().name());
        assertEquals(0, intacta.executada());
        assertEquals(7, intacta.cancelada());
        assertEquals(List.of(), fechar(motor, "ALFA3", DIA));
        rejeitada(motor.processar(ordem("ALFA3", Lado.VENDA, 1, "10.00", DIA)), "SESSAO_ENCERRADA");
    }

    @Test
    void novaSessaoReiniciaPrioridadeNegociosEBaseSemOfertasAntigas() {
        MotorMatching motor = new MotorMatching(ATIVOS);
        motor.processar(ordem("ALFA3", Lado.VENDA, 2, "10.50", DIA));
        motor.processar(ordem("ALFA3", Lado.COMPRA, 1, "10.50", DIA));
        fechar(motor, "ALFA3", DIA);
        Admissao venda = ordem("ALFA3", Lado.VENDA, 1, "11.10", DIA.plusDays(1));
        assertEquals(new Evento.OrdemAceita(venda.ordem(), "ALFA3", 1), motor.processar(venda).getFirst());
        Admissao market = new Admissao(UUID.randomUUID(), UUID.randomUUID(), "CONTA-003", "ALFA3", Lado.COMPRA,
                TipoOrdem.MARKET, Validade.IOC, 1, null, new BigDecimal("12.00"), DIA.plusDays(1));
        assertEquals(MotivoEncerramento.FORA_DA_FAIXA,
                assertInstanceOf(Evento.OrdemEncerrada.class, motor.processar(market).getLast()).motivo());
        List<Evento> execucao = motor.processar(ordem("ALFA3", Lado.COMPRA, 2, "11.10", DIA.plusDays(1)));
        List<Evento.Negocio> negocios = execucao.stream().filter(Evento.Negocio.class::isInstance)
                .map(Evento.Negocio.class::cast).toList();
        assertEquals(1, negocios.size());
        assertEquals("ALFA3-2026-10-20-1", negocios.getFirst().id());
        assertEquals(venda.ordem(), negocios.getFirst().ordemVenda());
        assertEquals(List.of(), fechar(motor, "ALFA3", DIA), "fechamento antigo não toca a sessão nova");
        rejeitada(motor.processar(ordem("ALFA3", Lado.VENDA, 1, "10.00", DIA)), "SESSAO_ANTERIOR");
    }

    @Test
    void fecharAtivoNaoAlteraOutroAtivo() {
        MotorMatching motor = new MotorMatching(ATIVOS);
        motor.processar(ordem("ALFA3", Lado.VENDA, 1, "10.00", DIA));
        Admissao beta = ordem("BETA3", Lado.VENDA, 1, "10.00", DIA);
        motor.processar(beta);
        fechar(motor, "ALFA3", DIA);
        List<Evento> compra = motor.processar(ordem("BETA3", Lado.COMPRA, 1, "10.00", DIA));
        assertTrue(compra.stream().anyMatch(e -> e instanceof Evento.Negocio n && n.ordemVenda().equals(beta.ordem())));
    }

    @Test
    void fechamentoErradoNaoMutaLivroNemFronteira() {
        MotorMatching motor = new MotorMatching(ATIVOS);
        Admissao venda = ordem("ALFA3", Lado.VENDA, 1, "10.00", DIA);
        motor.processar(venda);
        assertThrows(IllegalArgumentException.class, () -> fechar(motor, "ZETA3", DIA));
        assertThrows(IllegalArgumentException.class, () -> fechar(motor, "ALFA3", DIA.plusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> fechar(motor, "ALFA3", DIA.minusDays(1)));
        assertEquals(venda.ordem(), assertInstanceOf(Evento.OrdemEncerrada.class,
                fechar(motor, "ALFA3", DIA).getFirst()).ordem());
        assertThrows(IllegalArgumentException.class, () -> fechar(motor, "ALFA3", DIA.plusDays(1)));
        assertInstanceOf(Evento.OrdemAceita.class,
                motor.processar(ordem("ALFA3", Lado.COMPRA, 1, "10.00", DIA.plusDays(1))).getFirst());
    }

    @Test
    void ativoVazioConhecidoPodeFecharESuaFronteiraImpedeReabertura() {
        MotorMatching motor = new MotorMatching(ATIVOS);
        assertEquals(List.of(), fechar(motor, "ALFA3", DIA));
        assertEquals(List.of(), fechar(motor, "ALFA3", DIA.minusDays(1)));
        rejeitada(motor.processar(ordem("ALFA3", Lado.VENDA, 1, "10.00", DIA)), "SESSAO_ENCERRADA");
        rejeitada(motor.processar(ordem("ALFA3", Lado.VENDA, 1, "10.00", DIA.minusDays(1))), "SESSAO_ANTERIOR");
        assertInstanceOf(Evento.OrdemAceita.class,
                motor.processar(ordem("ALFA3", Lado.VENDA, 1, "10.00", DIA.plusDays(1))).getFirst());
    }

    @Test
    void entradaInvalidaNaoAbreSessaoEFechamentoRecusaNulosSemCriarFronteira() {
        MotorMatching motor = new MotorMatching(ATIVOS);
        assertThrows(IllegalArgumentException.class, () -> fechar(motor, null, DIA));
        assertThrows(IllegalArgumentException.class, () -> fechar(motor, "ALFA3", null));
        rejeitada(motor.processar(ordem("ALFA3", Lado.VENDA, 1, "0.00", DIA.plusDays(2))), "PRECO_INVALIDO");
        assertEquals(List.of(), fechar(motor, "ALFA3", DIA));
        rejeitada(motor.processar(ordem("ALFA3", Lado.VENDA, 1, "10.00", DIA)), "SESSAO_ENCERRADA");
        assertInstanceOf(Evento.OrdemAceita.class,
                motor.processar(ordem("ALFA3", Lado.VENDA, 1, "10.00", DIA.plusDays(1))).getFirst());
    }

    @Test
    void expiracaoLiberaTodasAsVagasDeOrdensAtivasNaNovaSessao() {
        MotorMatching motor = new MotorMatching(ATIVOS);
        for (int dia = 0; dia < 2; dia++) {
            LocalDate data = DIA.plusDays(dia);
            for (int i = 0; i < MotorMatching.MAX_ORDENS_ATIVAS_POR_CONTA; i++) {
                assertInstanceOf(Evento.OrdemAceita.class,
                        motor.processar(ordem("ALFA3", Lado.VENDA, 1, "10.00", data)).getFirst());
            }
            rejeitada(motor.processar(ordem("ALFA3", Lado.VENDA, 1, "10.00", data)), "LIMITE_ORDENS_ATIVAS");
            assertEquals(MotorMatching.MAX_ORDENS_ATIVAS_POR_CONTA, fechar(motor, "ALFA3", data).size());
        }
    }

    @Test
    void deduplicacaoGlobalSobreviveAoFechamentoEAoInicioDaProximaSessao() {
        MotorMatching motor = new MotorMatching(ATIVOS);
        Admissao antiga = ordem("ALFA3", Lado.VENDA, 1, "10.00", DIA);
        motor.processar(antiga);
        fechar(motor, "ALFA3", DIA);
        assertEquals(List.of(), motor.processar(antiga));
        motor.processar(ordem("ALFA3", Lado.VENDA, 1, "10.00", DIA.plusDays(1)));
        assertEquals(List.of(), motor.processar(antiga));
        Admissao outroAtivo = new Admissao(antiga.ordem(), antiga.intencao(), antiga.conta(), "BETA3", antiga.lado(),
                antiga.tipo(), antiga.validade(), antiga.quantidade(), antiga.precoLimite(), null, DIA.plusDays(1));
        assertEquals(List.of(), motor.processar(outroAtivo));
    }
}
