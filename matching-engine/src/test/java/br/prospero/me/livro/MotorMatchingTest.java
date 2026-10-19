package br.prospero.me.livro;

import br.prospero.me.livro.dominio.Admissao;
import br.prospero.me.livro.dominio.Evento;
import br.prospero.me.livro.dominio.Evento.Negocio;
import br.prospero.me.livro.dominio.Evento.OfertaNoLivro;
import br.prospero.me.livro.dominio.Evento.OrdemAceita;
import br.prospero.me.livro.dominio.Evento.OrdemEncerrada;
import br.prospero.me.livro.dominio.Evento.OrdemRejeitada;
import br.prospero.me.livro.dominio.Lado;
import br.prospero.me.livro.dominio.MotivoEncerramento;
import br.prospero.me.livro.dominio.MotivoRejeicao;
import br.prospero.me.livro.dominio.MotorMatching;
import br.prospero.me.livro.dominio.ParametrosAtivo;
import br.prospero.me.livro.dominio.TipoOrdem;
import br.prospero.me.livro.dominio.Validade;
import br.prospero.me.livro.helper.FaixaPreco;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Casos de mesa da RF05a (ADR-013): especificação executável por tipo e validade. */
class MotorMatchingTest {
    static final LocalDate SESSAO = LocalDate.parse("2026-10-19");
    static final ParametrosAtivo ALFA = new ParametrosAtivo("ALFA3", new BigDecimal("0.01"), new BigDecimal("10.00"),
            BigDecimal.TEN);
    private MotorMatching motor;

    @BeforeEach
    void novo() {
        motor = new MotorMatching(Map.of("ALFA3", ALFA));
    }

    static BigDecimal d(String valor) {
        return valor == null ? null : new BigDecimal(valor);
    }

    static Admissao compra(String conta, TipoOrdem tipo, Validade validade, long qtd, String limite, String maximo) {
        return new Admissao(UUID.randomUUID(), UUID.randomUUID(), conta, "ALFA3", Lado.COMPRA, tipo, validade, qtd,
                d(limite), d(maximo), SESSAO);
    }

    static Admissao venda(String conta, TipoOrdem tipo, Validade validade, long qtd, String limite) {
        return new Admissao(UUID.randomUUID(), UUID.randomUUID(), conta, "ALFA3", Lado.VENDA, tipo, validade, qtd,
                d(limite), null, SESSAO);
    }

    static Admissao vendaLimit(long qtd, String preco) {
        return venda("CONTA-003", TipoOrdem.LIMIT, Validade.DIA, qtd, preco);
    }

    static Admissao compraLimit(long qtd, String preco) {
        return compra("CONTA-002", TipoOrdem.LIMIT, Validade.DIA, qtd, preco, preco);
    }

    static <T extends Evento> List<T> doTipo(List<Evento> eventos, Class<T> tipo) {
        return eventos.stream().filter(tipo::isInstance).map(tipo::cast).toList();
    }

    @Test
    void limitSemContraparteRepousaEPrecoTempoComPrecoPassivo() {
        Admissao v1 = vendaLimit(100, "10.05");
        Admissao v2 = vendaLimit(100, "10.05");
        Admissao v3 = vendaLimit(100, "10.03");
        assertEquals(List.of(new OrdemAceita(v1.ordem(), "ALFA3", 1),
                new OfertaNoLivro(v1.ordem(), "ALFA3", Lado.VENDA, d("10.05"), 100)), motor.processar(v1));
        motor.processar(v2);
        motor.processar(v3);
        // Compra a 10,10 de 250: 10,03 primeiro (melhor preço), depois 10,05 por ordem de chegada; preço passivo.
        Admissao c = compraLimit(250, "10.10");
        List<Negocio> negocios = doTipo(motor.processar(c), Negocio.class);
        assertEquals(List.of(v3.ordem(), v1.ordem(), v2.ordem()), negocios.stream().map(Negocio::ordemVenda).toList());
        assertEquals(List.of(d("10.03"), d("10.05"), d("10.05")), negocios.stream().map(Negocio::preco).toList());
        assertEquals(List.of(100L, 100L, 50L), negocios.stream().map(Negocio::quantidade).toList());
        assertEquals(List.of("ALFA3-2026-10-19-1", "ALFA3-2026-10-19-2", "ALFA3-2026-10-19-3"),
                negocios.stream().map(Negocio::id).toList());
        assertTrue(negocios.stream().allMatch(n -> n.agressor() == Lado.COMPRA && n.contaCompra().equals("CONTA-002")));
        // O restante de v2 continua no livro com prioridade: nova compra leva os 50 dele.
        List<Negocio> seguinte = doTipo(motor.processar(compraLimit(80, "10.05")), Negocio.class);
        assertEquals(1, seguinte.size());
        assertEquals(v2.ordem(), seguinte.getFirst().ordemVenda());
        assertEquals(50, seguinte.getFirst().quantidade());
    }

    @Test
    void encerramentosTrazemExecutadaECancelada() {
        Admissao v = vendaLimit(100, "10.00");
        motor.processar(v);
        Admissao c = compraLimit(100, "10.00");
        List<OrdemEncerrada> fim = doTipo(motor.processar(c), OrdemEncerrada.class);
        assertEquals(List.of(new OrdemEncerrada(v.ordem(), "ALFA3", MotivoEncerramento.EXECUTADA, 100, 0),
                new OrdemEncerrada(c.ordem(), "ALFA3", MotivoEncerramento.EXECUTADA, 100, 0)), fim);
    }

    @Test
    void iocCancelaASobraEFokVerificaAntesSemTocarOLivro() {
        motor.processar(vendaLimit(60, "10.00"));
        Admissao fok = compra("CONTA-002", TipoOrdem.LIMIT, Validade.FOK, 100, "10.00", "10.00");
        List<Evento> eventos = motor.processar(fok);
        assertEquals(new OrdemEncerrada(fok.ordem(), "ALFA3", MotivoEncerramento.FOK_SEM_LIQUIDEZ, 0, 100),
                eventos.getLast());
        assertTrue(doTipo(eventos, Negocio.class).isEmpty(), "FOK não executa parcialmente");
        Admissao ioc = compra("CONTA-002", TipoOrdem.LIMIT, Validade.IOC, 100, "10.00", "10.00");
        eventos = motor.processar(ioc);
        assertEquals(60, doTipo(eventos, Negocio.class).getFirst().quantidade());
        assertEquals(new OrdemEncerrada(ioc.ordem(), "ALFA3", MotivoEncerramento.SALDO_CANCELADO_IOC, 60, 40),
                eventos.getLast());
        Admissao fokOk = compra("CONTA-002", TipoOrdem.LIMIT, Validade.FOK, 10, "10.00", "10.00");
        motor.processar(vendaLimit(10, "10.00"));
        assertEquals(MotivoEncerramento.EXECUTADA, ((OrdemEncerrada) motor.processar(fokOk).getLast()).motivo());
    }

    @Test
    void marketCompraLimitadaPelaFaixaEPelaReserva() {
        motor.processar(vendaLimit(10, "10.90"));
        motor.processar(vendaLimit(10, "11.00"));
        motor.processar(vendaLimit(10, "11.01"));
        // Sem negócio no ME, base = referência 10,00 → teto 11,00. Reserva até 11,00: leva 20, sobra fora da faixa.
        Admissao market = compra("CONTA-002", TipoOrdem.MARKET, Validade.IOC, 30, null, "11.00");
        List<Evento> eventos = motor.processar(market);
        assertEquals(2, doTipo(eventos, Negocio.class).size());
        assertEquals(new OrdemEncerrada(market.ordem(), "ALFA3", MotivoEncerramento.FORA_DA_FAIXA, 20, 10),
                eventos.getLast());
        // Reserva menor que o teto: compra nunca executa acima do preço máximo reservado.
        MotorMatching outro = new MotorMatching(Map.of("ALFA3", ALFA));
        outro.processar(vendaLimit(10, "10.50"));
        Admissao barata = compra("CONTA-002", TipoOrdem.MARKET, Validade.IOC, 10, null, "10.40");
        assertEquals(MotivoEncerramento.LIMITADA_PELA_RESERVA,
                ((OrdemEncerrada) outro.processar(barata).getLast()).motivo());
    }

    @Test
    void marketSemContraparteEVendaPeloPiso() {
        Admissao semNada = venda("CONTA-003", TipoOrdem.MARKET, Validade.IOC, 5, null);
        assertEquals(new OrdemEncerrada(semNada.ordem(), "ALFA3", MotivoEncerramento.SEM_LIQUIDEZ, 0, 5),
                motor.processar(semNada).getLast());
        motor.processar(compraLimit(5, "9.00"));
        motor.processar(compraLimit(5, "8.99"));
        Admissao venda = venda("CONTA-003", TipoOrdem.MARKET, Validade.FOK, 10, null);
        assertEquals(MotivoEncerramento.FOK_SEM_LIQUIDEZ, ((OrdemEncerrada) motor.processar(venda).getLast()).motivo(),
                "piso 9,00: 8,99 não conta para o FOK");
        Admissao ioc = venda("CONTA-003", TipoOrdem.MARKET, Validade.IOC, 10, null);
        List<Evento> eventos = motor.processar(ioc);
        assertEquals(d("9.00"), doTipo(eventos, Negocio.class).getFirst().preco());
        assertEquals(new OrdemEncerrada(ioc.ordem(), "ALFA3", MotivoEncerramento.FORA_DA_FAIXA, 5, 5),
                eventos.getLast());
    }

    @Test
    void faixaSegueOUltimoNegocioDoMe() {
        motor.processar(vendaLimit(1, "10.50"));
        motor.processar(compraLimit(1, "10.50"));
        // Base 10,50 → teto ⌊11,55⌋ = 11,55.
        motor.processar(vendaLimit(1, "11.55"));
        Admissao market = compra("CONTA-002", TipoOrdem.MARKET, Validade.IOC, 1, null, "11.55");
        assertEquals(MotivoEncerramento.EXECUTADA, ((OrdemEncerrada) motor.processar(market).getLast()).motivo());
        assertEquals(d("11.55"), FaixaPreco.teto(d("10.50"), d("0.01"), BigDecimal.TEN));
        assertEquals(d("9.45"), FaixaPreco.piso(d("10.50"), d("0.01"), BigDecimal.TEN));
        assertEquals(d("45.05"), FaixaPreco.piso(d("50.05"), d("0.05"), BigDecimal.TEN), "45,045 → 45,05");
    }

    @Test
    void autonegociacaoPermitida() {
        motor.processar(venda("CONTA-001", TipoOrdem.LIMIT, Validade.DIA, 10, "10.00"));
        List<Negocio> n = doTipo(motor.processar(compra("CONTA-001", TipoOrdem.LIMIT, Validade.DIA, 10, "10.00",
                "10.00")), Negocio.class);
        assertEquals("CONTA-001", n.getFirst().contaCompra());
        assertEquals("CONTA-001", n.getFirst().contaVenda());
    }

    @Test
    void rejeicoesNaAdmissao() {
        Admissao desconhecido = new Admissao(UUID.randomUUID(), UUID.randomUUID(), "CONTA-002", "ZETA3", Lado.VENDA,
                TipoOrdem.LIMIT, Validade.DIA, 1, d("1.00"), null, SESSAO);
        assertEquals(MotivoRejeicao.ATIVO_DESCONHECIDO, rejeicao(desconhecido));
        assertEquals(MotivoRejeicao.PRECO_ACIMA_DA_RESERVA,
                rejeicao(compra("CONTA-002", TipoOrdem.LIMIT, Validade.DIA, 1, "10.01", "10.00")));
        assertEquals(MotivoRejeicao.COMBINACAO_INVALIDA,
                rejeicao(compra("CONTA-002", TipoOrdem.MARKET, Validade.DIA, 1, null, "11.00")));
        assertEquals(MotivoRejeicao.COMBINACAO_INVALIDA,
                rejeicao(compra("CONTA-002", TipoOrdem.LIMIT, Validade.DIA, 1, "10.00", null)));
        assertEquals(MotivoRejeicao.COMBINACAO_INVALIDA,
                rejeicao(venda("CONTA-003", TipoOrdem.LIMIT, Validade.DIA, 1, null)));
        assertEquals(MotivoRejeicao.PRECO_INVALIDO, rejeicao(vendaLimit(1, "10.005")));
        assertEquals(MotivoRejeicao.PRECO_INVALIDO, rejeicao(vendaLimit(1, "0.00")));
        assertEquals(MotivoRejeicao.PRECO_INVALIDO, rejeicao(vendaLimit(1, "-1.00")));
        assertEquals(MotivoRejeicao.QUANTIDADE_INVALIDA, rejeicao(vendaLimit(MotorMatching.MAX_QUANTIDADE + 1, "10.00")));
        Admissao v = vendaLimit(1, "10.00");
        motor.processar(v);
        assertEquals(List.of(), motor.processar(v), "reentrega é ignorada: nenhuma rejeição terminal de ordem viva");
        for (int i = 0; i < MotorMatching.MAX_ORDENS_ATIVAS_POR_CONTA - 1; i++) {
            motor.processar(vendaLimit(1, "12.00"));
        }
        assertEquals(MotivoRejeicao.LIMITE_ORDENS_ATIVAS, rejeicao(vendaLimit(1, "12.00")));
    }

    @Test
    void fokNuncaExecutaParcialmentePeloTetoDeNegocios() {
        for (int i = 0; i < 300; i++) {
            motor.processar(vendaLimit(1, "10.00"));
        }
        Admissao fok = compra("CONTA-002", TipoOrdem.LIMIT, Validade.FOK, 300, "10.00", "10.00");
        List<Evento> eventos = motor.processar(fok);
        assertTrue(doTipo(eventos, Negocio.class).isEmpty());
        assertEquals(new OrdemEncerrada(fok.ordem(), "ALFA3", MotivoEncerramento.FOK_SEM_LIQUIDEZ, 0, 300),
                eventos.getLast());
        Admissao cabe = compra("CONTA-002", TipoOrdem.LIMIT, Validade.FOK, 256, "10.00", "10.00");
        assertEquals(MotivoEncerramento.EXECUTADA, ((OrdemEncerrada) motor.processar(cabe).getLast()).motivo());
    }

    @Test
    void sessaoNovaReiniciaIdsEBaseESessaoAnteriorERecusada() {
        motor.processar(vendaLimit(1, "10.50"));
        motor.processar(compraLimit(1, "10.50"));
        EncerramentoSessaoTest.fechar(motor, "ALFA3", SESSAO);
        LocalDate seguinte = SESSAO.plusDays(1);
        Admissao venda = new Admissao(UUID.randomUUID(), UUID.randomUUID(), "CONTA-003", "ALFA3", Lado.VENDA,
                TipoOrdem.LIMIT, Validade.DIA, 1, d("11.10"), null, seguinte);
        motor.processar(venda);
        // Base volta à referência 10,00 → teto 11,00: a oferta de 11,10 fica fora da faixa.
        Admissao market = new Admissao(UUID.randomUUID(), UUID.randomUUID(), "CONTA-002", "ALFA3", Lado.COMPRA,
                TipoOrdem.MARKET, Validade.IOC, 1, null, d("12.00"), seguinte);
        assertEquals(MotivoEncerramento.FORA_DA_FAIXA, ((OrdemEncerrada) motor.processar(market).getLast()).motivo());
        Admissao limit = new Admissao(UUID.randomUUID(), UUID.randomUUID(), "CONTA-002", "ALFA3", Lado.COMPRA,
                TipoOrdem.LIMIT, Validade.DIA, 1, d("11.10"), d("11.10"), seguinte);
        assertEquals("ALFA3-2026-10-20-1", doTipo(motor.processar(limit), Negocio.class).getFirst().id());
        assertEquals(MotivoRejeicao.SESSAO_ANTERIOR, rejeicao(compraLimit(1, "10.00")),
                "sessão atrasada não reinicia a sequência de IDs");
    }

    @Test
    void marketLimitadaPelaReservaTemMotivoProprio() {
        motor.processar(vendaLimit(10, "10.50"));
        Admissao barata = compra("CONTA-002", TipoOrdem.MARKET, Validade.IOC, 10, null, "10.40");
        assertEquals(MotivoEncerramento.LIMITADA_PELA_RESERVA,
                ((OrdemEncerrada) motor.processar(barata).getLast()).motivo());
    }

    @Test
    void parametrosDoAtivoInvalidosSaoRecusados() {
        for (String[] p : new String[][]{{"0", "10.00", "10"}, {"0.005", "10.00", "10"}, {"0.05", "10.01", "10"},
                {"0.01", "10.00", "0"}, {"0.01", "10.00", "51"}, {"0.01", "0", "10"}}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new ParametrosAtivo("X", d(p[0]), d(p[1]), d(p[2])));
        }
    }

    @Test
    void tetoArredondaParaDentroENaoPorProximidade() {
        assertEquals(d("11.05"), FaixaPreco.teto(d("10.05"), d("0.01"), BigDecimal.TEN), "11,055 → 11,05");
        assertEquals(d("9.05"), FaixaPreco.piso(d("10.05"), d("0.01"), BigDecimal.TEN), "9,045 → 9,05");
    }

    @Test
    void ordemExecutadaLiberaVagaNoLimiteDeAtivas() {
        for (int i = 0; i < MotorMatching.MAX_ORDENS_ATIVAS_POR_CONTA; i++) {
            motor.processar(vendaLimit(1, "12.00"));
        }
        assertEquals(MotivoRejeicao.LIMITE_ORDENS_ATIVAS, rejeicao(vendaLimit(1, "12.00")));
        motor.processar(compraLimit(1, "12.00"));
        assertTrue(motor.processar(vendaLimit(1, "12.00")).getFirst() instanceof OrdemAceita, "vaga liberada");
    }

    @Test
    void reentregaEhIgnoradaEmQualquerAtivo() {
        Admissao v = vendaLimit(1, "10.00");
        motor.processar(v);
        MotorMatching dois = new MotorMatching(Map.of("ALFA3", ALFA, "OMEGA3", new ParametrosAtivo("OMEGA3",
                d("0.05"), d("50.00"), BigDecimal.TEN)));
        dois.processar(v);
        Admissao outroAtivo = new Admissao(v.ordem(), v.intencao(), v.conta(), "OMEGA3", v.lado(), v.tipo(),
                v.validade(), v.quantidade(), d("50.00"), null, v.sessao());
        assertEquals(List.of(), dois.processar(outroAtivo));
    }

    private MotivoRejeicao rejeicao(Admissao a) {
        List<Evento> eventos = motor.processar(a);
        assertEquals(1, eventos.size());
        return ((OrdemRejeitada) eventos.getFirst()).motivo();
    }

    @Test
    void limiteDeNegociosPorComando() {
        for (int i = 0; i < MotorMatching.MAX_NEGOCIOS_POR_COMANDO + 5; i++) {
            motor.processar(venda("CONTA-00" + (2 + i % 2), TipoOrdem.LIMIT, Validade.DIA, 1, "10.00"));
        }
        Admissao grande = compraLimit(300, "10.00");
        List<Evento> eventos = motor.processar(grande);
        assertEquals(MotorMatching.MAX_NEGOCIOS_POR_COMANDO, doTipo(eventos, Negocio.class).size());
        assertEquals(new OrdemEncerrada(grande.ordem(), "ALFA3", MotivoEncerramento.LIMITE_NEGOCIOS, 256, 44),
                eventos.getLast());
    }
}
