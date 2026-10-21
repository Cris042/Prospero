package br.prospero.me.livro;

import br.prospero.me.livro.dominio.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** RF05c.1b: contratos públicos do núcleo, com IDs reproduzíveis e sem infraestrutura. */
class StopsMatchingTest {
    private static final LocalDate DIA = LocalDate.of(2026, 10, 20);
    private static final Map<String, ParametrosAtivo> ATIVOS = Map.of(
            "ALFA3", new ParametrosAtivo("ALFA3", d("0.01"), d("10.00"), BigDecimal.TEN),
            "BETA3", new ParametrosAtivo("BETA3", d("0.05"), d("50.00"), BigDecimal.TEN));
    private int id;

    private static BigDecimal d(String valor) { return valor == null ? null : new BigDecimal(valor); }
    private Admissao ordem(Lado lado, TipoOrdem tipo, long qtd, String limite, String gatilho) {
        return ordem("ALFA3", "C" + lado, lado, tipo, Validade.DIA, qtd, limite,
                lado == Lado.COMPRA ? "100.00" : null, gatilho, DIA);
    }
    private Admissao ordem(String ativo, String conta, Lado lado, TipoOrdem tipo, Validade validade,
                           long qtd, String limite, String maximo, String gatilho, LocalDate dia) {
        int n = ++id;
        return new Admissao(UUID.nameUUIDFromBytes(("ordem" + n).getBytes(StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes(("intencao" + n).getBytes(StandardCharsets.UTF_8)), conta, ativo,
                lado, tipo, validade, qtd, d(limite), d(maximo), d(gatilho), dia);
    }
    private Admissao limit(Lado lado, long qtd, String preco) { return ordem(lado, TipoOrdem.LIMIT, qtd, preco, null); }
    private static <T> List<T> tipos(List<Evento> eventos, Class<T> tipo) {
        return eventos.stream().filter(tipo::isInstance).map(tipo::cast).toList();
    }
    private static List<Evento.OrdemEncerrada> finais(List<Evento> eventos, Admissao a) {
        return tipos(eventos, Evento.OrdemEncerrada.class).stream().filter(e -> e.ordem().equals(a.ordem())).toList();
    }
    private void negocio(MotorMatching motor, String preco) {
        motor.processar(limit(Lado.VENDA, 1, preco));
        motor.processar(limit(Lado.COMPRA, 1, preco));
    }

    @Test
    void referenciaEOfertasNaoDisparamSemNegocioEIgualdadeDisparaAmbosOsLados() {
        for (Lado lado : Lado.values()) {
            MotorMatching m = new MotorMatching(ATIVOS);
            Admissao stop = ordem(lado, TipoOrdem.STOP, 3, null, "10.00");
            assertEquals(List.of(new Evento.OrdemAceita(stop.ordem(), "ALFA3", 1),
                    new Evento.StopPendente(stop.ordem(), "ALFA3", d("10.00"))), m.processar(stop));
            Admissao venda = limit(Lado.VENDA, 1, "10.00");
            assertTrue(tipos(m.processar(venda), Evento.StopAcionado.class).isEmpty());
            List<Evento> eventos = m.processar(limit(Lado.COMPRA, 1, "10.00"));
            assertEquals(1, tipos(eventos, Evento.StopAcionado.class).size());
            assertEquals(TipoOrdem.MARKET, tipos(eventos, Evento.StopAcionado.class).getFirst().tipoExecutavel());
            assertEquals(new Evento.OrdemEncerrada(stop.ordem(), "ALFA3", MotivoEncerramento.SEM_LIQUIDEZ, 0, 3),
                    finais(eventos, stop).getFirst());
            assertTrue(m.processar(stop).isEmpty());
        }
    }

    @Test
    void satisfeitoNaEntradaExecutaImediatamenteMantendoReservaEIdentidade() {
        MotorMatching m = new MotorMatching(ATIVOS);
        negocio(m, "10.00");
        m.processar(limit(Lado.VENDA, 2, "10.50"));
        m.processar(limit(Lado.VENDA, 2, "10.51"));
        Admissao stop = ordem("ALFA3", "RESERVADA", Lado.COMPRA, TipoOrdem.STOP, Validade.DIA,
                4, null, "10.50", "10.00", DIA);
        List<Evento> eventos = m.processar(stop);
        assertTrue(tipos(eventos, Evento.StopPendente.class).isEmpty());
        Evento.Negocio n = tipos(eventos, Evento.Negocio.class).getFirst();
        assertEquals(stop.ordem(), n.ordemCompra());
        assertEquals("RESERVADA", n.contaCompra());
        assertEquals(d("10.50"), n.preco());
        assertEquals(new Evento.OrdemEncerrada(stop.ordem(), "ALFA3", MotivoEncerramento.LIMITADA_PELA_RESERVA, 2, 2),
                finais(eventos, stop).getFirst());
    }

    @Test
    void agressorTerminaAntesDoStopEStopLimitPerdePrioridadeOriginal() {
        MotorMatching m = new MotorMatching(ATIVOS);
        Admissao stop = ordem(Lado.COMPRA, TipoOrdem.STOP_LIMIT, 2, "10.00", "10.00");
        m.processar(stop);
        Admissao anterior = limit(Lado.COMPRA, 1, "9.99");
        m.processar(anterior);
        m.processar(limit(Lado.VENDA, 1, "10.00"));
        m.processar(limit(Lado.VENDA, 1, "10.01"));
        Admissao agressor = limit(Lado.COMPRA, 2, "10.01");
        List<Evento> eventos = m.processar(agressor);
        assertEquals(List.of(agressor.ordem(), agressor.ordem()), tipos(eventos, Evento.Negocio.class).stream()
                .map(Evento.Negocio::ordemCompra).toList());
        assertTrue(eventos.indexOf(finais(eventos, agressor).getFirst()) < eventos.indexOf(
                tipos(eventos, Evento.StopAcionado.class).getFirst()));
        assertTrue(tipos(eventos, Evento.StopAcionado.class).getFirst().sequencia() > 2);
        assertEquals(stop.ordem(), tipos(m.processar(limit(Lado.VENDA, 1, "10.00")), Evento.Negocio.class)
                .getFirst().ordemCompra());
        // Uma compra no mesmo nível admitida depois do stop pendente, mas antes do acionamento, tem prioridade.
        MotorMatching outro = new MotorMatching(ATIVOS);
        Admissao s = ordem(Lado.VENDA, TipoOrdem.STOP_LIMIT, 2, "10.00", "10.00");
        outro.processar(s);
        Admissao antiga = limit(Lado.VENDA, 2, "10.00");
        outro.processar(antiga);
        outro.processar(limit(Lado.COMPRA, 1, "10.00"));
        assertEquals(antiga.ordem(), tipos(outro.processar(limit(Lado.COMPRA, 1, "10.00")), Evento.Negocio.class)
                .getFirst().ordemVenda());
    }

    @Test
    void gatilhoTransitorioPermaneceCapturadoMesmoSePrecoInverter() {
        MotorMatching m = new MotorMatching(ATIVOS);
        Admissao stop = ordem(Lado.VENDA, TipoOrdem.STOP_LIMIT, 1, "9.99", "10.00");
        m.processar(stop);
        m.processar(limit(Lado.VENDA, 1, "10.00"));
        m.processar(limit(Lado.VENDA, 1, "10.01"));
        List<Evento> eventos = m.processar(limit(Lado.COMPRA, 2, "10.01"));
        assertEquals(List.of(stop.ordem()), tipos(eventos, Evento.StopAcionado.class).stream()
                .map(Evento.StopAcionado::ordem).toList());
        assertTrue(tipos(eventos, Evento.OfertaNoLivro.class).stream().anyMatch(e -> e.ordem().equals(stop.ordem())));
    }

    @Test
    void cascataSeleciona32EmChegadaECancelaExcedentesComQuantidadesExatas() {
        for (int total : new int[]{31, 32, 33}) {
            MotorMatching m = new MotorMatching(ATIVOS);
            List<Admissao> stops = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                Admissao s = ordem(Lado.COMPRA, TipoOrdem.STOP, 2, null, "10.00");
                stops.add(s); m.processar(s);
            }
            m.processar(limit(Lado.VENDA, 1, "10.00"));
            List<Evento> eventos = m.processar(limit(Lado.COMPRA, 1, "10.00"));
            assertEquals(stops.stream().limit(32).map(Admissao::ordem).toList(),
                    tipos(eventos, Evento.StopAcionado.class).stream().map(Evento.StopAcionado::ordem).toList());
            for (int i = 0; i < total; i++) {
                assertEquals(i < 32 ? MotivoEncerramento.SEM_LIQUIDEZ : MotivoEncerramento.LIMITE_CASCATA,
                        finais(eventos, stops.get(i)).getFirst().motivo());
                assertEquals(2, finais(eventos, stops.get(i)).getFirst().cancelada());
            }
        }
    }

    @Test
    void teto256CompartilhadoEntreAgressorEStopMesmoSemLiquidezAoFinal() {
        for (int total : new int[]{255, 256, 257}) {
            MotorMatching m = new MotorMatching(ATIVOS);
            Admissao stop = ordem(Lado.COMPRA, TipoOrdem.STOP, 300, null, "10.00");
            m.processar(stop);
            for (int i = 0; i < total; i++) m.processar(limit(Lado.VENDA, 1, "10.00"));
            List<Evento> eventos = m.processar(limit(Lado.COMPRA, 1, "10.00"));
            assertEquals(Math.min(total, 256), tipos(eventos, Evento.Negocio.class).size());
            Evento.OrdemEncerrada fim = finais(eventos, stop).getFirst();
            assertEquals(total < 256 ? MotivoEncerramento.SEM_LIQUIDEZ : MotivoEncerramento.LIMITE_NEGOCIOS, fim.motivo());
            assertEquals(Math.min(total - 1, 255), fim.executada());
            assertEquals(300 - fim.executada(), fim.cancelada());
        }
        MotorMatching m = new MotorMatching(ATIVOS);
        Admissao stop = ordem(Lado.COMPRA, TipoOrdem.STOP, 1, null, "10.00");
        m.processar(stop);
        for (int i = 0; i < 256; i++) m.processar(limit(Lado.VENDA, 1, "10.00"));
        List<Evento> eventos = m.processar(limit(Lado.COMPRA, 256, "10.00"));
        assertEquals(new Evento.OrdemEncerrada(stop.ordem(), "ALFA3", MotivoEncerramento.LIMITE_NEGOCIOS, 0, 1),
                finais(eventos, stop).getFirst());
    }

    @Test
    void pendentesLimitados500PorAtivoE1000AtivasIncluemLivroSemDuplaContagem() {
        MotorMatching m = new MotorMatching(ATIVOS);
        for (int i = 0; i < 500; i++) m.processar(ordem(Lado.COMPRA, TipoOrdem.STOP, 1, null, "11.00"));
        List<Evento> rejeitada = m.processar(ordem(Lado.COMPRA, TipoOrdem.STOP, 1, null, "11.00"));
        assertEquals(MotivoRejeicao.LIMITE_STOPS_PENDENTES, tipos(rejeitada, Evento.OrdemRejeitada.class).getFirst().motivo());
        for (int i = 0; i < 500; i++) m.processar(limit(Lado.COMPRA, 1, "9.00"));
        rejeitada = m.processar(limit(Lado.COMPRA, 1, "9.00"));
        assertEquals(MotivoRejeicao.LIMITE_ORDENS_ATIVAS, tipos(rejeitada, Evento.OrdemRejeitada.class).getFirst().motivo());
        assertEquals(1000, m.encerrarSessao("ALFA3", DIA).size());
        Admissao nova = ordem("ALFA3", "CCOMPRA", Lado.COMPRA, TipoOrdem.STOP, Validade.DIA, 1, null, "100.00", "11.00", DIA.plusDays(1));
        assertInstanceOf(Evento.OrdemAceita.class, m.processar(nova).getFirst());
    }

    @Test
    void fechamentoMistoExpiraEmChegadaPreservaDedupEIsolaAtivos() {
        MotorMatching m = new MotorMatching(ATIVOS);
        Admissao stop = ordem(Lado.COMPRA, TipoOrdem.STOP, 3, null, "11.00");
        Admissao oferta = limit(Lado.VENDA, 2, "12.00");
        Admissao outro = ordem("BETA3", "B", Lado.VENDA, TipoOrdem.STOP, Validade.DIA, 5, null, null, "45.00", DIA);
        m.processar(stop); m.processar(oferta); m.processar(outro);
        assertEquals(List.of(new Evento.OrdemEncerrada(stop.ordem(), "ALFA3", MotivoEncerramento.EXPIRADA_DIA, 0, 3),
                new Evento.OrdemEncerrada(oferta.ordem(), "ALFA3", MotivoEncerramento.EXPIRADA_DIA, 0, 2)),
                m.encerrarSessao("ALFA3", DIA));
        assertTrue(m.encerrarSessao("ALFA3", DIA).isEmpty());
        assertTrue(m.processar(stop).isEmpty());
        assertEquals(1, m.encerrarSessao("BETA3", DIA).size());
    }

    @Test
    void cascataEncadeadaMantemTetoAcumuladoMesmoQuandoFilaDiminui() {
        MotorMatching m = new MotorMatching(ATIVOS);
        List<Admissao> primeiraOnda = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            Admissao s = ordem(Lado.COMPRA, TipoOrdem.STOP_LIMIT, 1, "11.00", "10.00");
            primeiraOnda.add(s); m.processar(s);
        }
        Admissao trigesimaSegunda = ordem(Lado.COMPRA, TipoOrdem.STOP, 1, null, "11.00");
        Admissao excedente = ordem(Lado.COMPRA, TipoOrdem.STOP, 2, null, "11.00");
        m.processar(trigesimaSegunda); m.processar(excedente);
        m.processar(limit(Lado.VENDA, 1, "10.00"));
        m.processar(limit(Lado.VENDA, 31, "11.00"));
        List<Evento> eventos = m.processar(limit(Lado.COMPRA, 1, "10.00"));
        List<UUID> esperado = new ArrayList<>(primeiraOnda.stream().map(Admissao::ordem).toList());
        esperado.add(trigesimaSegunda.ordem());
        assertEquals(esperado, tipos(eventos, Evento.StopAcionado.class).stream().map(Evento.StopAcionado::ordem).toList());
        assertEquals(32, tipos(eventos, Evento.Negocio.class).size());
        assertEquals(new Evento.OrdemEncerrada(excedente.ordem(), "ALFA3", MotivoEncerramento.LIMITE_CASCATA, 0, 2),
                finais(eventos, excedente).getFirst());
        assertTrue(m.encerrarSessao("ALFA3", DIA).isEmpty());
    }

    @Test
    void fokNaoContaStopPendenteComoLiquidezENaoAcionaSemNegocio() {
        MotorMatching m = new MotorMatching(ATIVOS);
        Admissao stop = ordem(Lado.VENDA, TipoOrdem.STOP_LIMIT, 10, "10.00", "10.00");
        m.processar(stop);
        m.processar(limit(Lado.VENDA, 1, "10.00"));
        Admissao fok = ordem("ALFA3", "FOK", Lado.COMPRA, TipoOrdem.LIMIT, Validade.FOK,
                2, "10.00", "10.00", null, DIA);
        List<Evento> eventos = m.processar(fok);
        assertTrue(tipos(eventos, Evento.Negocio.class).isEmpty());
        assertTrue(tipos(eventos, Evento.StopAcionado.class).isEmpty());
        assertEquals(MotivoEncerramento.FOK_SEM_LIQUIDEZ, finais(eventos, fok).getFirst().motivo());
        assertEquals(1, tipos(m.processar(limit(Lado.COMPRA, 1, "10.00")), Evento.StopAcionado.class).size());
    }

    @Test
    void ativacaoNaContaCheiaTransfereVagaSemRejeitarOuDuplicar() {
        MotorMatching m = new MotorMatching(ATIVOS);
        Admissao stop = ordem(Lado.COMPRA, TipoOrdem.STOP_LIMIT, 1, "10.00", "10.00");
        m.processar(stop);
        for (int i = 0; i < 999; i++) m.processar(limit(Lado.COMPRA, 1, "9.00"));
        m.processar(limit(Lado.VENDA, 1, "10.00"));
        Admissao raiz = ordem("ALFA3", "OUTRA", Lado.COMPRA, TipoOrdem.LIMIT, Validade.DIA,
                1, "10.00", "10.00", null, DIA);
        assertEquals(List.of(stop.ordem()), tipos(m.processar(raiz), Evento.StopAcionado.class).stream()
                .map(Evento.StopAcionado::ordem).toList());
        assertEquals(MotivoRejeicao.LIMITE_ORDENS_ATIVAS, tipos(m.processar(limit(Lado.COMPRA, 1, "9.00")),
                Evento.OrdemRejeitada.class).getFirst().motivo());
        assertEquals(1000, m.encerrarSessao("ALFA3", DIA).size());
    }

    @Test
    void oraculoIndependenteOrdenaPorNegocioDisparadorDepoisChegadaEReplayComparaTodosEventos() {
        for (long semente = 1; semente <= 50; semente++) {
            Random r = new Random(semente);
            MotorMatching m = new MotorMatching(ATIVOS), replay = new MotorMatching(ATIVOS);
            List<Admissao> pendentes = new ArrayList<>();
            List<Admissao> comandos = new ArrayList<>();
            int quantidade = 25 + r.nextInt(21);
            for (int i = 0; i < quantidade; i++) {
                String gatilho = "10.0" + r.nextInt(6);
                Admissao stop = ordem(Lado.COMPRA, TipoOrdem.STOP, 1 + r.nextInt(5), null, gatilho);
                pendentes.add(stop); comandos.add(stop);
            }
            for (int i = 0; i < 5; i++) comandos.add(limit(Lado.VENDA, 1, "10.0" + i));
            Admissao raiz = limit(Lado.COMPRA, 5, "10.04");
            comandos.add(raiz);
            List<Evento> resultado = List.of();
            for (Admissao comando : comandos) {
                resultado = m.processar(comando);
                assertEquals(resultado, replay.processar(comando), "replay semente=" + semente);
                assertTrue(m.processar(comando).isEmpty(), "duplicata semente=" + semente);
                assertTrue(replay.processar(comando).isEmpty());
            }
            // Modelo usa lista e ordenação estável; não usa índices nem seleção da produção.
            List<Admissao> disparadas = pendentes.stream().filter(s -> s.precoGatilho().compareTo(d("10.04")) <= 0)
                    .sorted(Comparator.comparing(Admissao::precoGatilho)).toList();
            assertEquals(disparadas.stream().limit(32).map(Admissao::ordem).toList(),
                    tipos(resultado, Evento.StopAcionado.class).stream().map(Evento.StopAcionado::ordem).toList(),
                    "oráculo de acionamento semente=" + semente);
            assertEquals(5, tipos(resultado, Evento.Negocio.class).size());
            for (int i = 0; i < disparadas.size(); i++) {
                Admissao s = disparadas.get(i);
                assertEquals(List.of(new Evento.OrdemEncerrada(s.ordem(), "ALFA3",
                        i < 32 ? MotivoEncerramento.SEM_LIQUIDEZ : MotivoEncerramento.LIMITE_CASCATA,
                        0, s.quantidade())), finais(resultado, s), "terminal semente=" + semente);
            }
            List<Evento> fim = m.encerrarSessao("ALFA3", DIA);
            assertEquals(fim, replay.encerrarSessao("ALFA3", DIA));
            assertEquals(pendentes.stream().filter(s -> s.precoGatilho().compareTo(d("10.04")) > 0)
                    .map(s -> (Evento) new Evento.OrdemEncerrada(s.ordem(), "ALFA3", MotivoEncerramento.EXPIRADA_DIA,
                            0, s.quantidade())).toList(), fim, "pendentes semente=" + semente);
        }
    }

    @Test
    void cap500SoContaPendentesEAtivacaoImediataNaoOcupaPendencia() {
        MotorMatching m = new MotorMatching(ATIVOS);
        negocio(m, "10.00");
        for (int i = 0; i < 500; i++) m.processar(ordem(Lado.COMPRA, TipoOrdem.STOP, 1, null, "11.00"));
        Admissao imediato = ordem(Lado.COMPRA, TipoOrdem.STOP, 1, null, "10.00");
        List<Evento> eventos = m.processar(imediato);
        assertEquals(1, tipos(eventos, Evento.StopAcionado.class).size());
        assertTrue(tipos(eventos, Evento.OrdemRejeitada.class).isEmpty());
        Admissao outroAtivo = ordem("BETA3", "CCOMPRA", Lado.COMPRA, TipoOrdem.STOP, Validade.DIA,
                1, null, "100.00", "55.00", DIA);
        assertInstanceOf(Evento.StopPendente.class, m.processar(outroAtivo).getLast());
        assertEquals(500, m.encerrarSessao("ALFA3", DIA).size());
        assertEquals(1, m.encerrarSessao("BETA3", DIA).size());
    }

    @Test
    void fechamentoUsaPrioridadeNovaDaConvertidaEProximaSessaoNaoHerdaGatilho() {
        MotorMatching m = new MotorMatching(ATIVOS);
        Admissao convertida = ordem(Lado.COMPRA, TipoOrdem.STOP_LIMIT, 3, "10.00", "10.00");
        Admissao pendente = ordem(Lado.COMPRA, TipoOrdem.STOP, 5, null, "11.00");
        m.processar(convertida); m.processar(pendente);
        negocio(m, "10.00");
        m.processar(limit(Lado.VENDA, 1, "10.00"));
        assertEquals(List.of(new Evento.OrdemEncerrada(pendente.ordem(), "ALFA3", MotivoEncerramento.EXPIRADA_DIA, 0, 5),
                new Evento.OrdemEncerrada(convertida.ordem(), "ALFA3", MotivoEncerramento.EXPIRADA_DIA, 1, 2)),
                m.encerrarSessao("ALFA3", DIA));
        Admissao nova = ordem("ALFA3", "NOVA", Lado.COMPRA, TipoOrdem.STOP, Validade.DIA,
                1, null, "100.00", "10.00", DIA.plusDays(1));
        List<Evento> eventos = m.processar(nova);
        assertEquals(List.of(new Evento.OrdemAceita(nova.ordem(), "ALFA3", 1),
                new Evento.StopPendente(nova.ordem(), "ALFA3", d("10.00"))), eventos);
        Admissao identidadeAntiga = new Admissao(convertida.ordem(), convertida.intencao(), "OUTRA", "BETA3",
                Lado.VENDA, TipoOrdem.STOP, Validade.DIA, 1, null, null, d("50.00"), DIA.plusDays(1));
        assertTrue(m.processar(identidadeAntiga).isEmpty());
    }

    @Test
    void stopVendaConverteMarketIocComPisoESobraCancelada() {
        MotorMatching m = new MotorMatching(ATIVOS);
        negocio(m, "10.00");
        m.processar(limit(Lado.COMPRA, 1, "9.00"));
        m.processar(limit(Lado.COMPRA, 1, "8.99"));
        Admissao stop = ordem(Lado.VENDA, TipoOrdem.STOP, 3, null, "10.00");
        List<Evento> eventos = m.processar(stop);
        assertEquals(List.of(d("9.00")), tipos(eventos, Evento.Negocio.class).stream().map(Evento.Negocio::preco).toList());
        assertEquals(new Evento.OrdemEncerrada(stop.ordem(), "ALFA3", MotivoEncerramento.FORA_DA_FAIXA, 1, 2),
                finais(eventos, stop).getFirst());
        assertTrue(tipos(eventos, Evento.OfertaNoLivro.class).isEmpty());
    }

    @Test
    void rejeitaMatrizTickExtremosECoerenciaSemNegociar() {
        MotorMatching m = new MotorMatching(ATIVOS);
        List<Admissao> invalidas = List.of(
                ordem(Lado.COMPRA, TipoOrdem.STOP, 1, null, null),
                ordem(Lado.COMPRA, TipoOrdem.STOP, 1, "10.00", "10.00"),
                ordem(Lado.COMPRA, TipoOrdem.STOP_LIMIT, 1, null, "10.00"),
                ordem(Lado.COMPRA, TipoOrdem.STOP_LIMIT, 1, "9.99", "10.00"),
                ordem(Lado.VENDA, TipoOrdem.STOP_LIMIT, 1, "10.01", "10.00"),
                ordem(Lado.VENDA, TipoOrdem.STOP, 1, null, "10.001"),
                ordem(Lado.VENDA, TipoOrdem.STOP, 1, null, "0"),
                ordem(Lado.VENDA, TipoOrdem.STOP, 1, null, "1E+100000"),
                ordem("BETA3", "B", Lado.VENDA, TipoOrdem.STOP, Validade.DIA, 1, null, null, "50.01", DIA),
                ordem("ALFA3", "A", Lado.VENDA, TipoOrdem.STOP, Validade.IOC, 1, null, null, "10.00", DIA),
                ordem("ALFA3", "A", Lado.VENDA, TipoOrdem.STOP_LIMIT, Validade.FOK, 1, "10.00", null, "10.00", DIA));
        for (Admissao a : invalidas) {
            List<Evento> eventos = m.processar(a);
            assertEquals(1, eventos.size(), a.toString());
            assertInstanceOf(Evento.OrdemRejeitada.class, eventos.getFirst(), a.toString());
        }
    }
}
