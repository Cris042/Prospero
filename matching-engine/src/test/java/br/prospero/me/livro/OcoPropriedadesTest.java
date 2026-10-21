package br.prospero.me.livro;

import br.prospero.me.livro.dominio.Admissao;
import br.prospero.me.livro.dominio.AdmissaoOco;
import br.prospero.me.livro.dominio.Evento;
import br.prospero.me.livro.dominio.Lado;
import br.prospero.me.livro.dominio.MotivoEncerramento;
import br.prospero.me.livro.dominio.MotivoRejeicao;
import br.prospero.me.livro.dominio.MotorMatching;
import br.prospero.me.livro.dominio.ParametrosAtivo;
import br.prospero.me.livro.dominio.TipoOrdem;
import br.prospero.me.livro.dominio.Validade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * RF05c.3a (QA independente): propriedades com sementes fixas misturando pares OCO, ordens simples e stops, mais casos
 * focais das decisões do ADR-013 que não tinham teste dedicado. O verificador reconstrói o estado só a partir dos
 * eventos e confere invariantes; não reimplementa o motor.
 */
class OcoPropriedadesTest {
    private static final int SEMENTES = 250;
    private static final int COMANDOS = 250;
    private static final LocalDate DIA = LocalDate.of(2026, 10, 21);
    private static final BigDecimal TICK = new BigDecimal("0.01");
    private static final Map<String, ParametrosAtivo> ATIVOS = Map.of(
            "ALFA3", new ParametrosAtivo("ALFA3", TICK, new BigDecimal("10.00"), BigDecimal.TEN));
    private static final List<String> CONTAS = List.of("C0", "C1", "C2");

    // ---------------------------------------------------------------- gerador

    private static BigDecimal d(String valor) { return valor == null ? null : new BigDecimal(valor); }
    private static BigDecimal ticks(long n) { return TICK.multiply(BigDecimal.valueOf(n)); }
    private static UUID uuid(String texto) { return UUID.nameUUIDFromBytes(texto.getBytes(StandardCharsets.UTF_8)); }

    private static Admissao admissao(String id, String conta, Lado lado, TipoOrdem tipo, Validade validade, long qtd,
                                     BigDecimal limite, BigDecimal maximo, BigDecimal gatilho) {
        return new Admissao(uuid("o" + id), uuid("i" + id), conta, "ALFA3", lado, tipo, validade, qtd, limite,
                lado == Lado.COMPRA ? maximo : null, gatilho, DIA);
    }

    /** Comandos: Admissao (simples) ou AdmissaoOco (par). Repetições exercitam a reentrega. */
    static List<Object> gerar(long semente) {
        Random r = new Random(semente);
        List<Object> comandos = new ArrayList<>();
        List<Admissao> simples = new ArrayList<>();
        for (int i = 0; i < COMANDOS; i++) {
            String id = semente + "-" + i;
            if (!comandos.isEmpty() && r.nextInt(30) == 0) {
                comandos.add(comandos.get(r.nextInt(comandos.size())));
                continue;
            }
            String conta = CONTAS.get(r.nextInt(CONTAS.size()));
            Lado lado = r.nextBoolean() ? Lado.COMPRA : Lado.VENDA;
            boolean venda = lado == Lado.VENDA;
            long qtd = r.nextInt(8) == 0 ? 1 + r.nextInt(200) : 1 + r.nextInt(20);
            long preco = 940 + r.nextInt(121); // 9,40..10,60
            int k = r.nextInt(10);
            if (k < 3) {
                long gatilho = preco;
                long alvo = venda ? gatilho + 1 + r.nextInt(80) : gatilho - 1 - r.nextInt(80);
                boolean stopLimit = r.nextBoolean();
                long limiteStop = venda ? gatilho - r.nextInt(20) : gatilho + r.nextInt(20);
                BigDecimal maximo = d("12.00");
                boolean incoerente = r.nextInt(15) == 0;
                if (incoerente) {
                    alvo = venda ? gatilho - 1 : gatilho + 1;
                }
                UUID intencao = uuid("par" + id);
                Admissao tp = new Admissao(uuid("tp" + id), intencao, conta, "ALFA3", lado, TipoOrdem.LIMIT,
                        Validade.DIA, qtd, ticks(alvo), venda ? null : maximo, null, DIA);
                if (!simples.isEmpty() && r.nextInt(25) == 0) {
                    // Perna TP com identidade já usada por uma ordem simples: só a inédita é rejeitada.
                    Admissao usada = simples.get(r.nextInt(simples.size()));
                    tp = new Admissao(usada.ordem(), intencao, conta, "ALFA3", lado, TipoOrdem.LIMIT, Validade.DIA,
                            qtd, ticks(alvo), venda ? null : maximo, null, DIA);
                }
                Admissao sl = new Admissao(uuid("sl" + id), intencao, conta, "ALFA3", lado,
                        stopLimit ? TipoOrdem.STOP_LIMIT : TipoOrdem.STOP, Validade.DIA, qtd,
                        stopLimit ? ticks(limiteStop) : null, venda ? null : maximo, ticks(gatilho), DIA);
                comandos.add(new AdmissaoOco(tp, sl));
                continue;
            }
            Admissao a;
            if (k < 5) {
                boolean stopLimit = r.nextBoolean();
                long limite = venda ? preco - r.nextInt(20) : preco + r.nextInt(20);
                a = admissao(id, conta, lado, stopLimit ? TipoOrdem.STOP_LIMIT : TipoOrdem.STOP, Validade.DIA, qtd,
                        stopLimit ? ticks(limite) : null, stopLimit ? ticks(limite) : d("11.50"), ticks(preco));
            } else if (k < 6) {
                a = admissao(id, conta, lado, TipoOrdem.MARKET, r.nextBoolean() ? Validade.IOC : Validade.FOK, qtd,
                        null, d("11.50"), null);
            } else {
                Validade validade = r.nextInt(10) < 8 ? Validade.DIA : r.nextBoolean() ? Validade.IOC : Validade.FOK;
                a = admissao(id, conta, lado, TipoOrdem.LIMIT, validade, qtd, ticks(preco), ticks(preco), null);
            }
            simples.add(a);
            comandos.add(a);
        }
        return comandos;
    }

    static List<Evento> executar(MotorMatching motor, Object comando) {
        return comando instanceof AdmissaoOco par ? motor.processarOco(par) : motor.processar((Admissao) comando);
    }

    // ---------------------------------------------------------------- verificador por eventos

    /** Estado observado de uma ordem aceita, reconstruído só pelos eventos. */
    static final class Ordem {
        final Admissao admissao;
        long sequencia;
        long executada;
        long reduzida;
        boolean pendente;
        boolean capturada;
        boolean terminal;
        Lado ladoLivro;
        BigDecimal precoLivro;
        long restanteLivro;

        Ordem(Admissao admissao) { this.admissao = admissao; }

        boolean noLivro() { return precoLivro != null; }
        long saldo() { return admissao.quantidade() - executada - reduzida; }
    }

    static final class Verificador {
        final MotorMatching motor = new MotorMatching(ATIVOS);
        final Set<UUID> vistas = new HashSet<>();
        final Map<UUID, Ordem> ordens = new HashMap<>();
        /** perna -> irmã, para pares aceitos. */
        final Map<UUID, UUID> irma = new HashMap<>();
        final Set<UUID> stopLosses = new HashSet<>();
        final List<Evento> todos = new ArrayList<>();

        void comando(Object comando) {
            comando(comando, true);
        }

        void comando(Object comando, boolean invariantes) {
            List<Evento> eventos = executar(motor, comando);
            todos.addAll(eventos);
            if (comando instanceof AdmissaoOco par) {
                esperarPar(par, eventos);
            } else {
                esperarSimples((Admissao) comando, eventos);
            }
            if (invariantes) {
                invariantesDoEstado();
            }
        }

        private void esperarSimples(Admissao a, List<Evento> eventos) {
            if (!vistas.add(a.ordem())) {
                assertEquals(List.of(), eventos, "reentrega simples é inócua");
                return;
            }
            Map<UUID, Admissao> novas = Map.of(a.ordem(), a);
            aplicar(eventos, novas);
        }

        private void esperarPar(AdmissaoOco par, List<Evento> eventos) {
            Admissao tp = par.takeProfit(), sl = par.stopLoss();
            boolean tpInedita = !vistas.contains(tp.ordem()), slInedita = !vistas.contains(sl.ordem());
            vistas.add(tp.ordem());
            vistas.add(sl.ordem());
            if (!tpInedita && !slInedita) {
                assertEquals(List.of(), eventos, "reentrega do par é inócua");
                return;
            }
            if (tpInedita != slInedita) {
                Admissao inedita = tpInedita ? tp : sl;
                assertEquals(List.of(new Evento.OrdemRejeitada(inedita.ordem(), "ALFA3",
                        MotivoRejeicao.COMBINACAO_INVALIDA)), eventos, "só a perna inédita é rejeitada");
                return;
            }
            boolean venda = tp.lado() == Lado.VENDA;
            boolean coerente = venda ? tp.precoLimite().compareTo(sl.precoGatilho()) > 0
                    : tp.precoLimite().compareTo(sl.precoGatilho()) < 0;
            if (!coerente) {
                assertEquals(List.of(
                        new Evento.OrdemRejeitada(tp.ordem(), "ALFA3", MotivoRejeicao.COMBINACAO_INVALIDA),
                        new Evento.OrdemRejeitada(sl.ordem(), "ALFA3", MotivoRejeicao.COMBINACAO_INVALIDA)), eventos);
                return;
            }
            assertTrue(eventos.size() >= 2, "par coerente produz eventos");
            assertTrue(eventos.get(0) instanceof Evento.OrdemAceita a0 && a0.ordem().equals(tp.ordem()),
                    "TP aceito primeiro: " + eventos);
            assertTrue(eventos.get(1) instanceof Evento.OrdemAceita a1 && a1.ordem().equals(sl.ordem())
                    && a1.sequencia() == ((Evento.OrdemAceita) eventos.get(0)).sequencia() + 1,
                    "SL aceito em seguida com sequência consecutiva: " + eventos);
            irma.put(tp.ordem(), sl.ordem());
            irma.put(sl.ordem(), tp.ordem());
            stopLosses.add(sl.ordem());
            aplicar(eventos, Map.of(tp.ordem(), tp, sl.ordem(), sl));
            Ordem tpEstado = ordens.get(tp.ordem()), slEstado = ordens.get(sl.ordem());
            // Fim do comando de admissão: SL pendente, capturado ou encerrado; TP no livro ou encerrado.
            assertTrue(slEstado.pendente || slEstado.capturada || slEstado.terminal, "SL sem destino");
            assertTrue(tpEstado.noLivro() || tpEstado.terminal, "TP sem destino");
        }

        private void aplicar(List<Evento> eventos, Map<UUID, Admissao> novas) {
            Set<UUID> capturadasNoComando = new HashSet<>();
            for (Evento e : eventos) {
                switch (e) {
                    case Evento.OrdemAceita x -> {
                        Admissao a = novas.get(x.ordem());
                        assertNotNull(a, "aceite de ordem que não é do comando");
                        assertFalse(ordens.containsKey(x.ordem()), "aceite duplicado");
                        Ordem o = new Ordem(a);
                        o.sequencia = x.sequencia();
                        ordens.put(x.ordem(), o);
                    }
                    case Evento.OrdemRejeitada x -> {
                        assertTrue(novas.containsKey(x.ordem()), "rejeição de ordem fora do comando");
                        assertFalse(ordens.containsKey(x.ordem()), "rejeição de ordem aceita");
                    }
                    case Evento.StopPendente x -> {
                        Ordem o = viva(x.ordem());
                        assertFalse(o.pendente || o.capturada, "stop pendente duas vezes");
                        o.pendente = true;
                    }
                    case Evento.StopAcionado x -> {
                        Ordem o = viva(x.ordem());
                        assertFalse(o.capturada, "acionado duas vezes");
                        o.pendente = false;
                        o.capturada = true;
                        o.sequencia = x.sequencia();
                        capturadasNoComando.add(x.ordem());
                        UUID tp = stopLosses.contains(x.ordem()) ? irma.get(x.ordem()) : null;
                        if (tp != null) {
                            assertTrue(ordens.get(tp).terminal, "TP vivo no dreno do SL acionado");
                        }
                    }
                    case Evento.OrdemReduzida x -> {
                        Ordem o = viva(x.ordem());
                        assertTrue(stopLosses.contains(x.ordem()), "só o SL é reduzido");
                        assertFalse(o.capturada, "SL capturado não pode ser reduzido");
                        assertTrue(x.reduzida() > 0, "redução positiva");
                        o.reduzida += x.reduzida();
                        assertEquals(o.saldo(), x.restante(), "restante da redução");
                        assertTrue(x.restante() > 0, "redução não zera o SL; o terminal do TP o encerra");
                        Ordem tp = ordens.get(irma.get(x.ordem()));
                        assertFalse(tp.terminal, "redução depois do terminal do TP");
                        assertEquals(tp.executada, o.reduzida, "SL reduzido exatamente pelo executado do TP");
                    }
                    case Evento.Negocio x -> {
                        assertTrue(x.quantidade() > 0);
                        Ordem compra = viva(x.ordemCompra()), venda = viva(x.ordemVenda());
                        Ordem passiva = x.agressor() == Lado.COMPRA ? venda : compra;
                        assertTrue(passiva.noLivro(), "passiva fora do livro");
                        assertEquals(0, passiva.precoLivro.compareTo(x.preco()), "negócio ao preço da passiva");
                        assertTrue(passiva.restanteLivro >= x.quantidade(), "passiva sem saldo");
                        passiva.restanteLivro -= x.quantidade();
                        for (Ordem o : List.of(compra, venda)) {
                            o.executada += x.quantidade();
                            assertTrue(o.executada + o.reduzida <= o.admissao.quantidade(), "execução além do saldo");
                            UUID outra = irma.get(o.admissao.ordem());
                            if (outra != null && stopLosses.contains(outra)) {
                                assertFalse(ordens.get(outra).capturada, "TP negociou depois da captura do SL");
                            }
                            if (stopLosses.contains(o.admissao.ordem())) {
                                assertTrue(o.capturada, "SL negociou sem captura");
                            }
                        }
                    }
                    case Evento.OfertaNoLivro x -> {
                        Ordem o = viva(x.ordem());
                        assertFalse(o.noLivro(), "oferta repousada duas vezes");
                        assertEquals(o.saldo(), x.restante(), "saldo que repousa");
                        o.ladoLivro = x.lado();
                        o.precoLivro = x.preco();
                        o.restanteLivro = x.restante();
                    }
                    case Evento.OrdemEncerrada x -> encerrar(x);
                }
            }
            // Ao fim do comando, cada par está inteiro, ou com um terminal e a outra perna encerrada/capturada.
            for (Map.Entry<UUID, UUID> par : irma.entrySet()) {
                Ordem a = ordens.get(par.getKey()), b = ordens.get(par.getValue());
                if (a.terminal && !b.terminal) {
                    // Única exceção: TP cancelado na captura e SL convertido ainda repousando como Limit.
                    boolean slCapturado = stopLosses.contains(b.admissao.ordem()) && b.capturada;
                    assertTrue(slCapturado,
                            "terminal de uma perna deixou a irmã viva: " + a.admissao.ordem());
                }
            }
        }

        private void encerrar(Evento.OrdemEncerrada x) {
            Ordem o = ordens.get(x.ordem());
            assertNotNull(o, "terminal de ordem não aceita");
            assertFalse(o.terminal, "mais de um terminal para " + x.ordem());
            o.terminal = true;
            assertEquals(o.executada, x.executada(), "executada do terminal = soma dos negócios");
            assertEquals(o.admissao.quantidade(), x.executada() + o.reduzida + x.cancelada(),
                    "conservação: executada + reduções + cancelada = quantidade");
            if (o.noLivro()) {
                assertEquals(o.restanteLivro, x.cancelada(), "cancelada = saldo no livro");
            }
            o.precoLivro = null;
            o.pendente = false;
            if (x.motivo() == MotivoEncerramento.CANCELADA_OCO) {
                UUID outra = irma.get(x.ordem());
                assertNotNull(outra, "CANCELADA_OCO fora de par");
                if (stopLosses.contains(x.ordem())) {
                    assertFalse(o.capturada, "SL capturado não é cancelado pela irmã");
                    assertEquals(0, x.executada());
                }
                // TP cancelado: o destino do SL (captura ou LIMITE_CASCATA) é conferido ao fim do comando.
            }
            if (x.motivo() == MotivoEncerramento.EXECUTADA) {
                assertEquals(0, x.cancelada());
            }
        }

        private Ordem viva(UUID ordem) {
            Ordem o = ordens.get(ordem);
            assertNotNull(o, "evento de ordem não aceita");
            assertFalse(o.terminal, "evento depois do terminal: " + ordem);
            return o;
        }

        private void invariantesDoEstado() {
            BigDecimal compra = null, venda = null;
            for (Ordem o : ordens.values()) {
                if (o.noLivro()) {
                    assertTrue(o.restanteLivro > 0, "oferta vazia no livro");
                    if (o.ladoLivro == Lado.COMPRA) {
                        compra = compra == null || o.precoLivro.compareTo(compra) > 0 ? o.precoLivro : compra;
                    } else {
                        venda = venda == null || o.precoLivro.compareTo(venda) < 0 ? o.precoLivro : venda;
                    }
                }
                if (!o.terminal) {
                    assertTrue(o.noLivro() || o.pendente, "ordem viva sem livro nem pendência ao fim do comando");
                }
            }
            if (compra != null && venda != null) {
                assertTrue(compra.compareTo(venda) < 0, "livro cruzado: " + compra + " >= " + venda);
            }
        }

        long vivas(String conta) {
            return ordens.values().stream().filter(o -> !o.terminal && o.admissao.conta().equals(conta)).count();
        }

        long pendentes() {
            return ordens.values().stream().filter(o -> o.pendente).count();
        }

        /** Sondas: vagas ativas por conta e pendentes por ativo batem exatamente com o observado. */
        void sondarVagas(String rotulo) {
            int n = 0;
            for (String conta : CONTAS) {
                long livres = MotorMatching.MAX_ORDENS_ATIVAS_POR_CONTA - vivas(conta);
                for (long i = 0; i < livres; i++) {
                    Admissao sonda = admissao(rotulo + "-a-" + n++, conta, Lado.COMPRA, TipoOrdem.LIMIT, Validade.DIA,
                            1, TICK, TICK, null);
                    comando(sonda, false);
                    assertTrue(ordens.containsKey(sonda.ordem()), "vaga ativa negada, conta " + conta);
                }
                Admissao excesso = admissao(rotulo + "-a-" + n++, conta, Lado.COMPRA, TipoOrdem.LIMIT, Validade.DIA, 1,
                        TICK, TICK, null);
                assertEquals(List.of(new Evento.OrdemRejeitada(excesso.ordem(), "ALFA3",
                        MotivoRejeicao.LIMITE_ORDENS_ATIVAS)), motor.processar(excesso), "conta " + conta);
                vistas.add(excesso.ordem());
            }
            long livres = MotorMatching.MAX_STOPS_PENDENTES - pendentes();
            for (long i = 0; i < livres; i++) {
                Admissao sonda = admissao(rotulo + "-s-" + n++, "SONDA", Lado.VENDA, TipoOrdem.STOP, Validade.DIA, 1,
                        null, null, TICK);
                comando(sonda, false);
                assertTrue(ordens.get(sonda.ordem()).pendente, "vaga pendente negada");
            }
            Admissao excesso = admissao(rotulo + "-s-" + n, "SONDA", Lado.VENDA, TipoOrdem.STOP, Validade.DIA, 1,
                    null, null, TICK);
            assertEquals(List.of(new Evento.OrdemRejeitada(excesso.ordem(), "ALFA3",
                    MotivoRejeicao.LIMITE_STOPS_PENDENTES)), motor.processar(excesso));
        }

        /** Fechamento: expira exatamente as vivas, em sequência, conservando reduções. */
        void fechar() {
            List<Evento> eventos = motor.encerrarSessao("ALFA3", DIA);
            long anterior = -1;
            Set<UUID> esperadas = new HashSet<>();
            ordens.values().stream().filter(o -> !o.terminal).forEach(o -> esperadas.add(o.admissao.ordem()));
            Set<UUID> expiradas = new HashSet<>();
            for (Evento e : eventos) {
                Evento.OrdemEncerrada x = (Evento.OrdemEncerrada) e;
                assertEquals(MotivoEncerramento.EXPIRADA_DIA, x.motivo());
                Ordem o = ordens.get(x.ordem());
                assertTrue(o.sequencia > anterior, "expiração fora da sequência de chegada");
                anterior = o.sequencia;
                expiradas.add(x.ordem());
                encerrar(x);
            }
            assertEquals(esperadas, expiradas, "fechamento expira exatamente as vivas");
        }
    }

    // ---------------------------------------------------------------- propriedades

    @Test
    void invariantesOcoComOrdensSimplesEStopsEmSementesFixas() {
        int pares = 0, reducoes = 0, cancelamentos = 0, capturas = 0;
        for (long semente = 1; semente <= SEMENTES; semente++) {
            Verificador v = new Verificador();
            try {
                for (Object comando : gerar(semente)) {
                    v.comando(comando);
                }
                if (semente % 5 == 0) {
                    v.sondarVagas("sonda" + semente);
                }
                v.fechar();
            } catch (AssertionError erro) {
                fail("semente " + semente + ": " + erro.getMessage(), erro);
            }
            pares += v.stopLosses.size();
            for (Evento e : v.todos) {
                if (e instanceof Evento.OrdemReduzida) reducoes++;
                if (e instanceof Evento.OrdemEncerrada x && x.motivo() == MotivoEncerramento.CANCELADA_OCO) cancelamentos++;
                if (e instanceof Evento.StopAcionado x && v.stopLosses.contains(x.ordem())) capturas++;
            }
        }
        // O gerador precisa de fato exercitar os caminhos OCO; senão a propriedade é vazia.
        assertTrue(pares > 10000 && reducoes > 2000 && cancelamentos > 8000 && capturas > 5000,
                "cobertura do gerador: pares=" + pares + " reducoes=" + reducoes + " cancelamentos=" + cancelamentos
                        + " capturas=" + capturas);
    }

    @Test
    void mesmaSequenciaMistaGeraMesmosEventos() {
        for (long semente = 1; semente <= 60; semente++) {
            List<Object> comandos = gerar(semente);
            assertEquals(replay(comandos), replay(comandos), "semente " + semente);
        }
    }

    private static List<Evento> replay(List<Object> comandos) {
        MotorMatching motor = new MotorMatching(ATIVOS);
        List<Evento> eventos = new ArrayList<>();
        comandos.forEach(c -> eventos.addAll(executar(motor, c)));
        eventos.addAll(motor.encerrarSessao("ALFA3", DIA));
        return eventos;
    }

    // ---------------------------------------------------------------- casos focais

    private int seq;

    private Admissao limit(String conta, Lado lado, long qtd, String preco) {
        return admissao("f" + ++seq, conta, lado, TipoOrdem.LIMIT, Validade.DIA, qtd, d(preco), d("100.00"), null);
    }

    private AdmissaoOco parVenda(long qtd, String alvo, String gatilho) {
        int n = ++seq;
        UUID intencao = uuid("fpar" + n);
        return new AdmissaoOco(
                new Admissao(uuid("ftp" + n), intencao, "INVESTIDOR", "ALFA3", Lado.VENDA, TipoOrdem.LIMIT,
                        Validade.DIA, qtd, d(alvo), null, null, DIA),
                new Admissao(uuid("fsl" + n), intencao, "INVESTIDOR", "ALFA3", Lado.VENDA, TipoOrdem.STOP,
                        Validade.DIA, qtd, null, null, d(gatilho), DIA));
    }

    private static List<Evento.OrdemEncerrada> finais(List<Evento> eventos, UUID ordem) {
        return eventos.stream().filter(e -> e instanceof Evento.OrdemEncerrada x && x.ordem().equals(ordem))
                .map(Evento.OrdemEncerrada.class::cast).toList();
    }

    @Test
    void takeProfitAgressorNoTetoDeNegociosEncerraOStopLossComReducoes() {
        MotorMatching m = new MotorMatching(ATIVOS);
        for (int i = 0; i < MotorMatching.MAX_NEGOCIOS_POR_COMANDO; i++) {
            m.processar(limit("COMPRADOR", Lado.COMPRA, 1, "10.50"));
        }
        AdmissaoOco par = parVenda(300, "10.50", "9.00");
        List<Evento> eventos = m.processarOco(par);
        assertEquals(List.of(new Evento.OrdemEncerrada(par.takeProfit().ordem(), "ALFA3",
                MotivoEncerramento.LIMITE_NEGOCIOS, 256, 44)), finais(eventos, par.takeProfit().ordem()));
        long reduzido = eventos.stream().filter(Evento.OrdemReduzida.class::isInstance)
                .mapToLong(e -> ((Evento.OrdemReduzida) e).reduzida()).sum();
        assertEquals(256, reduzido);
        assertEquals(List.of(new Evento.OrdemEncerrada(par.stopLoss().ordem(), "ALFA3",
                MotivoEncerramento.CANCELADA_OCO, 0, 44)), finais(eventos, par.stopLoss().ordem()));
        assertTrue(eventos.stream().noneMatch(Evento.StopPendente.class::isInstance));
        assertTrue(eventos.indexOf(finais(eventos, par.takeProfit().ordem()).getFirst())
                < eventos.indexOf(finais(eventos, par.stopLoss().ordem()).getFirst()));
    }

    /** Distingue cancelar o TP na captura de cancelá-lo só no dreno: um stop anterior na fila acharia o TP. */
    @Test
    void takeProfitSaiDoLivroNaCapturaAntesDeStopsAnterioresDaFilaNegociarem() {
        MotorMatching m = new MotorMatching(ATIVOS);
        m.processar(limit("X", Lado.COMPRA, 1, "10.00"));
        m.processar(limit("X", Lado.COMPRA, 1, "9.50"));
        Admissao stopCompra = admissao("f" + ++seq, "Y", Lado.COMPRA, TipoOrdem.STOP, Validade.DIA, 3, null,
                d("11.00"), d("10.00"));
        m.processar(stopCompra);
        AdmissaoOco par = parVenda(5, "10.40", "9.50");
        m.processarOco(par);
        List<Evento> eventos = m.processar(limit("Z", Lado.VENDA, 2, "9.50"));
        Evento.OrdemEncerrada tp = finais(eventos, par.takeProfit().ordem()).getFirst();
        assertEquals(new Evento.OrdemEncerrada(par.takeProfit().ordem(), "ALFA3", MotivoEncerramento.CANCELADA_OCO, 0,
                5), tp);
        Evento.StopAcionado primeiro = eventos.stream().filter(Evento.StopAcionado.class::isInstance)
                .map(Evento.StopAcionado.class::cast).findFirst().orElseThrow();
        assertEquals(stopCompra.ordem(), primeiro.ordem());
        assertTrue(eventos.indexOf(tp) < eventos.indexOf(primeiro), "TP cancelado na captura, antes do dreno");
        assertTrue(eventos.stream().noneMatch(e -> e instanceof Evento.Negocio n
                && n.ordemVenda().equals(par.takeProfit().ordem())), "TP não negocia depois da captura do SL");
        assertEquals(List.of(new Evento.OrdemEncerrada(stopCompra.ordem(), "ALFA3", MotivoEncerramento.SEM_LIQUIDEZ,
                0, 3)), finais(eventos, stopCompra.ordem()));
    }

    @Test
    void vagaPendenteDoParVoltaAposExecucaoTotalDoTakeProfit() {
        MotorMatching m = new MotorMatching(ATIVOS);
        for (int i = 0; i < MotorMatching.MAX_STOPS_PENDENTES - 1; i++) {
            m.processar(admissao("f" + ++seq, "S" + (i % 400), Lado.VENDA, TipoOrdem.STOP, Validade.DIA, 1, null,
                    null, d("5.00")));
        }
        AdmissaoOco par = parVenda(2, "12.00", "9.00");
        assertTrue(m.processarOco(par).stream().anyMatch(Evento.StopPendente.class::isInstance));
        Admissao semVaga = admissao("f" + ++seq, "S", Lado.VENDA, TipoOrdem.STOP, Validade.DIA, 1, null, null,
                d("5.00"));
        assertEquals(List.of(new Evento.OrdemRejeitada(semVaga.ordem(), "ALFA3",
                MotivoRejeicao.LIMITE_STOPS_PENDENTES)), m.processar(semVaga));
        List<Evento> total = m.processar(limit("COMPRADOR", Lado.COMPRA, 2, "12.00"));
        assertEquals(MotivoEncerramento.CANCELADA_OCO, finais(total, par.stopLoss().ordem()).getFirst().motivo());
        Admissao cabe = admissao("f" + ++seq, "S", Lado.VENDA, TipoOrdem.STOP, Validade.DIA, 1, null, null,
                d("5.00"));
        assertTrue(m.processar(cabe).stream().anyMatch(Evento.StopPendente.class::isInstance),
                "SL cancelado devolve a vaga pendente");
    }

    /** Vaga pendente exigida na admissão mesmo quando o SL seria capturado de imediato (decisão conservadora). */
    @Test
    void parSemVagaPendenteERejeitadoMesmoComStopLossJaAcionavel() {
        MotorMatching m = new MotorMatching(ATIVOS);
        for (int i = 0; i < MotorMatching.MAX_STOPS_PENDENTES; i++) {
            m.processar(admissao("f" + ++seq, "S" + (i % 400), Lado.VENDA, TipoOrdem.STOP, Validade.DIA, 1, null,
                    null, d("5.00")));
        }
        m.processar(limit("X", Lado.VENDA, 1, "9.00"));
        m.processar(limit("Y", Lado.COMPRA, 1, "9.00"));
        AdmissaoOco par = parVenda(3, "12.00", "9.00");
        assertEquals(List.of(
                new Evento.OrdemRejeitada(par.takeProfit().ordem(), "ALFA3", MotivoRejeicao.LIMITE_STOPS_PENDENTES),
                new Evento.OrdemRejeitada(par.stopLoss().ordem(), "ALFA3", MotivoRejeicao.LIMITE_STOPS_PENDENTES)),
                m.processarOco(par));
    }

    @Test
    void contagemDeAtivasVoltaAZeroAposCapturaECascata() {
        MotorMatching m = new MotorMatching(ATIVOS);
        for (int i = 0; i < MotorMatching.MAX_ATIVACOES_POR_COMANDO; i++) {
            m.processar(admissao("f" + ++seq, "S" + i, Lado.VENDA, TipoOrdem.STOP, Validade.DIA, 1, null, null,
                    d("9.00")));
        }
        AdmissaoOco cascata = parVenda(3, "12.00", "9.00");
        AdmissaoOco captura = parVenda(3, "12.50", "8.00");
        m.processarOco(cascata);
        m.processarOco(captura);
        m.processar(limit("X", Lado.VENDA, 1, "9.00"));
        List<Evento> eventos = m.processar(limit("Y", Lado.COMPRA, 1, "9.00"));
        assertEquals(MotivoEncerramento.LIMITE_CASCATA, finais(eventos, cascata.stopLoss().ordem()).getFirst().motivo());
        m.processar(limit("X", Lado.VENDA, 1, "8.00"));
        List<Evento> segundo = m.processar(limit("Y", Lado.COMPRA, 1, "8.00"));
        assertEquals(MotivoEncerramento.CANCELADA_OCO, finais(segundo, captura.takeProfit().ordem()).getFirst().motivo());
        assertEquals(1, finais(segundo, captura.stopLoss().ordem()).size(), "SL capturado encerra no dreno");
        for (int i = 0; i < MotorMatching.MAX_ORDENS_ATIVAS_POR_CONTA; i++) {
            List<Evento> r = m.processar(limit("INVESTIDOR", Lado.COMPRA, 1, "0.01"));
            assertTrue(r.stream().noneMatch(Evento.OrdemRejeitada.class::isInstance), "vaga " + i);
        }
    }

    @Test
    void parDeAtivoDesconhecidoRejeitaAsDuasPernasEReentregaEInocua() {
        MotorMatching m = new MotorMatching(ATIVOS);
        AdmissaoOco base = parVenda(1, "12.00", "9.00");
        Admissao tp = base.takeProfit(), sl = base.stopLoss();
        AdmissaoOco par = new AdmissaoOco(
                new Admissao(tp.ordem(), tp.intencao(), tp.conta(), "ZZZZ3", tp.lado(), tp.tipo(), tp.validade(), 1,
                        tp.precoLimite(), null, null, DIA),
                new Admissao(sl.ordem(), sl.intencao(), sl.conta(), "ZZZZ3", sl.lado(), sl.tipo(), sl.validade(), 1,
                        null, null, sl.precoGatilho(), DIA));
        assertEquals(List.of(new Evento.OrdemRejeitada(tp.ordem(), "ZZZZ3", MotivoRejeicao.ATIVO_DESCONHECIDO),
                new Evento.OrdemRejeitada(sl.ordem(), "ZZZZ3", MotivoRejeicao.ATIVO_DESCONHECIDO)), m.processarOco(par));
        assertEquals(List.of(), m.processarOco(par));
        assertEquals(List.of(), m.processar(sl));
    }
}
