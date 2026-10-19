package br.prospero.me.livro;

import br.prospero.me.livro.dominio.Admissao;
import br.prospero.me.livro.dominio.Evento;
import br.prospero.me.livro.dominio.Lado;
import br.prospero.me.livro.dominio.MotivoEncerramento;
import br.prospero.me.livro.dominio.MotivoRejeicao;
import br.prospero.me.livro.dominio.MotorMatching;
import br.prospero.me.livro.dominio.ParametrosAtivo;
import br.prospero.me.livro.dominio.TipoOrdem;
import br.prospero.me.livro.dominio.Validade;
import br.prospero.me.livro.helper.FaixaPreco;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Propriedades da RF05a com gerador próprio e sementes fixas (ADR-013); a semente aparece na falha para reproduzir.
 * Um modelo independente mantém o livro esperado e funciona como oráculo: prevê quantidade executada, motivo de
 * encerramento, contraparte (preço-tempo) e ID de cada negócio antes de comparar com os eventos do motor.
 */
class PropriedadesMatchingTest {
    private static final int SEMENTES = 200;
    private static final int COMANDOS = 300;
    private static final LocalDate D1 = LocalDate.parse("2026-10-19");
    private static final LocalDate D2 = D1.plusDays(1);
    private static final Map<String, ParametrosAtivo> ATIVOS = Map.of(
            "ALFA3", new ParametrosAtivo("ALFA3", new BigDecimal("0.01"), new BigDecimal("10.00"), BigDecimal.TEN),
            "OMEGA3", new ParametrosAtivo("OMEGA3", new BigDecimal("0.05"), new BigDecimal("50.00"), BigDecimal.TEN));

    static List<Admissao> gerar(long semente) {
        Random r = new Random(semente);
        List<Admissao> lista = new ArrayList<>();
        int n = 0;
        // Sementes múltiplas de 10 começam com um lote de vendas de 1 para exercitar o teto de 256 negócios.
        if (semente % 10 == 0) {
            for (int i = 0; i < 280; i++) {
                lista.add(admissao(semente, n++, "ALFA3", Lado.VENDA, TipoOrdem.LIMIT, Validade.DIA, 1,
                        new BigDecimal("10.00"), null, D1, "CONTA-003"));
            }
        }
        for (int i = 0; i < COMANDOS; i++) {
            if (!lista.isEmpty() && r.nextInt(40) == 0) {
                lista.add(lista.get(r.nextInt(lista.size())));
                continue;
            }
            LocalDate sessao = i < COMANDOS / 2 || r.nextInt(30) == 0 ? D1 : D2;
            String ticker = r.nextBoolean() ? "ALFA3" : "OMEGA3";
            ParametrosAtivo p = ATIVOS.get(ticker);
            Lado lado = r.nextBoolean() ? Lado.COMPRA : Lado.VENDA;
            boolean market = r.nextInt(5) == 0;
            Validade validade = market ? (r.nextBoolean() ? Validade.IOC : Validade.FOK)
                    : Validade.values()[r.nextInt(10) < 7 ? 0 : 1 + r.nextInt(2)];
            long ticks = p.referencia().divide(p.tick()).longValue();
            long desvio = Math.round(ticks * 0.12);
            BigDecimal preco = p.tick().multiply(BigDecimal.valueOf(ticks - desvio + r.nextInt((int) (2 * desvio + 1))));
            BigDecimal maximo = lado == Lado.VENDA ? null
                    : market ? p.tick().multiply(BigDecimal.valueOf(ticks + r.nextInt((int) desvio + 1))) : preco;
            long quantidade = r.nextInt(4) == 0 ? 1 : 1 + r.nextInt(r.nextInt(10) == 0 ? 400 : 300);
            lista.add(admissao(semente, n++, ticker, lado, market ? TipoOrdem.MARKET : TipoOrdem.LIMIT, validade,
                    quantidade, market ? null : preco, maximo, sessao, "CONTA-00" + (1 + r.nextInt(3))));
        }
        return lista;
    }

    static Admissao admissao(long semente, int i, String ticker, Lado lado, TipoOrdem tipo, Validade validade,
                             long quantidade, BigDecimal limite, BigDecimal maximo, LocalDate sessao, String conta) {
        return new Admissao(UUID.nameUUIDFromBytes(("o" + semente + "-" + i).getBytes()),
                UUID.nameUUIDFromBytes(("i" + semente + "-" + i).getBytes()), conta, ticker, lado, tipo, validade,
                quantidade, limite, maximo, sessao);
    }

    /** Oferta viva no modelo. */
    static final class Viva {
        final UUID ordem;
        final String conta;
        final long quantidade;
        final Lado lado;
        final BigDecimal preco;
        final long sequencia;
        long restante;

        Viva(UUID ordem, String conta, long quantidade, Lado lado, BigDecimal preco, long sequencia, long restante) {
            this.ordem = ordem;
            this.conta = conta;
            this.quantidade = quantidade;
            this.lado = lado;
            this.preco = preco;
            this.sequencia = sequencia;
            this.restante = restante;
        }
    }

    /** Estado esperado de um ativo. */
    static final class Modelo {
        final List<Viva> livro = new ArrayList<>();
        BigDecimal ultimo;
        LocalDate sessao;
        long negocios;
        long chegada;

        /** Ofertas do lado oposto em prioridade preço-tempo. */
        List<Viva> contrapartes(Lado agressor) {
            Comparator<Viva> preco = Comparator.comparing(v -> v.preco);
            return livro.stream().filter(v -> v.lado != agressor)
                    .sorted((agressor == Lado.COMPRA ? preco : preco.reversed())
                            .thenComparingLong(v -> v.sequencia)).toList();
        }
    }

    @Test
    void oraculoConfirmaCadaComandoEmSementesFixas() {
        for (long semente = 1; semente <= SEMENTES; semente++) {
            try {
                verificar(gerar(semente));
            } catch (AssertionError erro) {
                fail("semente " + semente + ": " + erro.getMessage(), erro);
            }
        }
    }

    @Test
    void mesmaSequenciaGeraMesmosEventos() {
        for (long semente = 1; semente <= 50; semente++) {
            List<Admissao> admissoes = gerar(semente);
            assertEquals(executar(admissoes), executar(admissoes), "semente " + semente);
        }
    }

    /** Impressão digital dos eventos de uma semente: muda se o comportamento observável mudar (RNF02). */
    @Test
    void eventosDeReferenciaDaSessaoUnicaNaoMudam() throws Exception {
        StringBuilder texto = new StringBuilder();
        // A impressão digital cobre uma sessão válida; viradas agora exigem comando explícito.
        executar(gerar(42).stream().filter(a -> a.sessao().equals(D1)).toList()).forEach(e -> texto.append(e).append('\n'));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(texto.toString().getBytes(StandardCharsets.UTF_8)));
        assertEquals(REFERENCIA_SEMENTE_42, hash);
    }

    // Baseline da mesma fixture D1 executada no motor anterior à RF05c.1a (log local do ciclo RED).
    static final String REFERENCIA_SEMENTE_42 = "0f6f876278c526b357ef67ba2249f425f469eed5b3fdeedfa324cefe2c486ef2";

    static List<Evento> executar(List<Admissao> admissoes) {
        MotorMatching motor = new MotorMatching(ATIVOS);
        List<Evento> todos = new ArrayList<>();
        admissoes.forEach(a -> todos.addAll(motor.processar(a)));
        return todos;
    }

    private static void verificar(List<Admissao> admissoes) {
        MotorMatching motor = new MotorMatching(ATIVOS);
        Map<String, Modelo> modelos = new HashMap<>();
        Set<UUID> vistas = new HashSet<>();
        Set<String> ids = new HashSet<>();
        verificar(admissoes, motor, modelos, vistas, ids);
    }

    private static void verificar(List<Admissao> admissoes, MotorMatching motor, Map<String, Modelo> modelos,
                                  Set<UUID> vistas, Set<String> ids) {
        for (Admissao a : admissoes) {
            List<Evento> eventos = motor.processar(a);
            if (!vistas.add(a.ordem())) {
                assertEquals(List.of(), eventos, "reentrega é ignorada");
                continue;
            }
            Modelo m = modelos.computeIfAbsent(a.ticker(), t -> new Modelo());
            if (m.sessao != null && a.sessao().isBefore(m.sessao)) {
                assertEquals(List.of(new Evento.OrdemRejeitada(a.ordem(), a.ticker(), MotivoRejeicao.SESSAO_ANTERIOR)),
                        eventos);
                continue;
            }
            if (m.sessao != null && a.sessao().isAfter(m.sessao)) {
                assertEquals(List.of(new Evento.OrdemRejeitada(a.ordem(), a.ticker(),
                        MotivoRejeicao.SESSAO_NAO_ENCERRADA)), eventos);
                continue;
            }
            if (!a.sessao().equals(m.sessao)) {
                m.sessao = a.sessao();
                m.negocios = 0;
                m.ultimo = null;
            }
            esperar(a, m, ATIVOS.get(a.ticker()), eventos, ids);
            naoCruzado(m);
        }
    }

    @Test
    void tresSessoesConservamSaldosExpiramEmSequenciaEPreservamDeduplicacao() {
        for (long semente = 1; semente <= 50; semente++) {
            MotorMatching motor = new MotorMatching(ATIVOS);
            Set<UUID> vistas = new HashSet<>();
            Set<String> ids = new HashSet<>();
            Admissao primeira = null;
            for (int dia = 0; dia < 3; dia++) {
                LocalDate data = D1.plusDays(dia);
                Map<String, Modelo> modelos = new HashMap<>();
                List<Admissao> lote = gerar(semente + dia * 1000).stream()
                        .filter(a -> a.sessao().equals(D1)).limit(80)
                        .map(a -> new Admissao(a.ordem(), a.intencao(), a.conta(), a.ticker(), a.lado(), a.tipo(),
                                a.validade(), a.quantidade(), a.precoLimite(), a.precoMaximo(), data)).toList();
                lote = new ArrayList<>(lote);
                for (String ticker : List.of("ALFA3", "OMEGA3")) {
                    final String ativo = ticker;
                    if (lote.stream().noneMatch(a -> a.ticker().equals(ativo))) {
                        lote.add(admissao(semente + dia * 1000, ticker.equals("ALFA3") ? -1 : -2, ticker,
                                Lado.VENDA, TipoOrdem.LIMIT, Validade.DIA, 1, ATIVOS.get(ticker).referencia(),
                                null, data, "CONTA-003"));
                    }
                }
                verificar(lote, motor, modelos, vistas, ids);
                if (primeira == null) {
                    primeira = lote.getFirst();
                }
                assertEquals(List.of(), motor.processar(primeira), "reentrega sobrevive à mudança de sessão");
                for (String ticker : List.of("ALFA3", "OMEGA3")) {
                    Modelo modelo = modelos.get(ticker);
                    List<Evento> esperados = modelo == null ? List.of() : modelo.livro.stream()
                            .sorted(Comparator.comparingLong(v -> v.sequencia))
                            .<Evento>map(v -> new Evento.OrdemEncerrada(v.ordem, ticker,
                                    MotivoEncerramento.EXPIRADA_DIA, v.quantidade - v.restante, v.restante))
                            .toList();
                    assertEquals(esperados, EncerramentoSessaoTest.fechar(motor, ticker, data),
                            "semente " + semente + ", sessão " + data + ", ativo " + ticker);
                    assertEquals(List.of(), EncerramentoSessaoTest.fechar(motor, ticker, D1));
                    assertEquals(List.of(), EncerramentoSessaoTest.fechar(motor, ticker, data));
                }
            }
        }
    }

    /** Simula o comando no modelo e compara evento a evento. */
    private static void esperar(Admissao a, Modelo m, ParametrosAtivo p, List<Evento> eventos, Set<String> ids) {
        BigDecimal limite;
        MotivoEncerramento corte;
        if (a.tipo() == TipoOrdem.LIMIT) {
            limite = a.precoLimite();
            corte = MotivoEncerramento.SALDO_CANCELADO_IOC;
        } else {
            BigDecimal base = m.ultimo != null ? m.ultimo : p.referencia();
            if (a.lado() == Lado.VENDA) {
                limite = FaixaPreco.piso(base, p.tick(), p.tunelPercentual());
                corte = MotivoEncerramento.FORA_DA_FAIXA;
            } else {
                BigDecimal teto = FaixaPreco.teto(base, p.tick(), p.tunelPercentual());
                corte = teto.compareTo(a.precoMaximo()) <= 0 ? MotivoEncerramento.FORA_DA_FAIXA
                        : MotivoEncerramento.LIMITADA_PELA_RESERVA;
                limite = teto.min(a.precoMaximo());
            }
        }
        final BigDecimal pior = limite;
        List<Viva> aceitaveis = m.contrapartes(a.lado()).stream().filter(v -> a.lado() == Lado.COMPRA
                ? v.preco.compareTo(pior) <= 0 : v.preco.compareTo(pior) >= 0).toList();
        List<Evento> esperados = new ArrayList<>();
        esperados.add(new Evento.OrdemAceita(a.ordem(), a.ticker(), ++m.chegada));
        // FOK: só executa se a quantidade inteira couber em até 256 negócios.
        long cobre = 0;
        for (int i = 0; i < Math.min(aceitaveis.size(), MotorMatching.MAX_NEGOCIOS_POR_COMANDO); i++) {
            cobre += aceitaveis.get(i).restante;
        }
        if (a.validade() == Validade.FOK && cobre < a.quantidade()) {
            esperados.add(new Evento.OrdemEncerrada(a.ordem(), a.ticker(), MotivoEncerramento.FOK_SEM_LIQUIDEZ, 0,
                    a.quantidade()));
            assertEquals(esperados, eventos);
            return;
        }
        long executada = 0;
        int negocios = 0;
        for (Viva passiva : aceitaveis) {
            if (executada == a.quantidade() || negocios == MotorMatching.MAX_NEGOCIOS_POR_COMANDO) {
                break;
            }
            long q = Math.min(a.quantidade() - executada, passiva.restante);
            executada += q;
            passiva.restante -= q;
            negocios++;
            m.ultimo = passiva.preco;
            boolean compra = a.lado() == Lado.COMPRA;
            String id = a.ticker() + "-" + m.sessao + "-" + (++m.negocios);
            assertTrue(ids.add(id), "ID de negócio repetido: " + id);
            if (a.lado() == Lado.VENDA && a.tipo() == TipoOrdem.MARKET) {
                assertTrue(passiva.preco.compareTo(pior) >= 0, "venda abaixo do piso");
            }
            esperados.add(new Evento.Negocio(id, a.ticker(), passiva.preco, q, compra ? a.ordem() : passiva.ordem,
                    compra ? a.conta() : passiva.conta, compra ? passiva.ordem : a.ordem(),
                    compra ? passiva.conta : a.conta(), a.lado()));
            if (passiva.restante == 0) {
                m.livro.remove(passiva);
                esperados.add(new Evento.OrdemEncerrada(passiva.ordem, a.ticker(), MotivoEncerramento.EXECUTADA,
                        passiva.quantidade, 0));
            }
        }
        long restante = a.quantidade() - executada;
        boolean sobraAceitavel = aceitaveis.stream().anyMatch(v -> v.restante > 0);
        if (restante == 0) {
            esperados.add(new Evento.OrdemEncerrada(a.ordem(), a.ticker(), MotivoEncerramento.EXECUTADA, executada, 0));
        } else if (sobraAceitavel) {
            esperados.add(new Evento.OrdemEncerrada(a.ordem(), a.ticker(), MotivoEncerramento.LIMITE_NEGOCIOS,
                    executada, restante));
        } else if (a.tipo() == TipoOrdem.LIMIT && a.validade() == Validade.DIA) {
            m.livro.add(new Viva(a.ordem(), a.conta(), a.quantidade(), a.lado(), a.precoLimite(), m.chegada, restante));
            esperados.add(new Evento.OfertaNoLivro(a.ordem(), a.ticker(), a.lado(), a.precoLimite(), restante));
        } else {
            boolean contraparte = !m.contrapartes(a.lado()).isEmpty();
            MotivoEncerramento motivo = a.tipo() == TipoOrdem.LIMIT || contraparte ? corte
                    : MotivoEncerramento.SEM_LIQUIDEZ;
            esperados.add(new Evento.OrdemEncerrada(a.ordem(), a.ticker(), motivo, executada, restante));
        }
        assertEquals(esperados, eventos);
    }

    private static void naoCruzado(Modelo m) {
        BigDecimal melhorCompra = m.livro.stream().filter(v -> v.lado == Lado.COMPRA).map(v -> v.preco)
                .max(BigDecimal::compareTo).orElse(null);
        BigDecimal melhorVenda = m.livro.stream().filter(v -> v.lado == Lado.VENDA).map(v -> v.preco)
                .min(BigDecimal::compareTo).orElse(null);
        m.livro.forEach(v -> assertTrue(v.restante > 0, "oferta vazia no livro"));
        if (melhorCompra != null && melhorVenda != null) {
            assertTrue(melhorCompra.compareTo(melhorVenda) < 0, "livro cruzado");
        }
    }
}
