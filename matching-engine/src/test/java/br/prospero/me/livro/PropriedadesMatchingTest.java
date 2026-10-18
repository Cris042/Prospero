package br.prospero.me.livro;

import br.prospero.me.livro.dominio.Admissao;
import br.prospero.me.livro.dominio.Evento;
import br.prospero.me.livro.dominio.Lado;
import br.prospero.me.livro.dominio.MotorMatching;
import br.prospero.me.livro.dominio.ParametrosAtivo;
import br.prospero.me.livro.dominio.TipoOrdem;
import br.prospero.me.livro.dominio.Validade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Propriedades da RF05a com gerador próprio e sementes fixas (ADR-013): a semente aparece na falha para reproduzir.
 * Um modelo independente, reconstruído só pelos eventos, confere conservação, preço passivo, FIFO, livro não cruzado,
 * faixa/reserva e determinismo (mesmas admissões → mesmos eventos).
 */
class PropriedadesMatchingTest {
    private static final int SEMENTES = 200;
    private static final int COMANDOS = 300;
    private static final Map<String, ParametrosAtivo> ATIVOS = Map.of(
            "ALFA3", new ParametrosAtivo("ALFA3", new BigDecimal("0.01"), new BigDecimal("10.00"), BigDecimal.TEN),
            "OMEGA3", new ParametrosAtivo("OMEGA3", new BigDecimal("0.05"), new BigDecimal("50.00"), BigDecimal.TEN));

    static List<Admissao> gerar(long semente) {
        Random r = new Random(semente);
        List<Admissao> lista = new ArrayList<>();
        LocalDate sessao = LocalDate.parse("2026-10-19");
        for (int i = 0; i < COMANDOS; i++) {
            String ticker = r.nextBoolean() ? "ALFA3" : "OMEGA3";
            ParametrosAtivo p = ATIVOS.get(ticker);
            Lado lado = r.nextBoolean() ? Lado.COMPRA : Lado.VENDA;
            boolean market = r.nextInt(5) == 0;
            Validade validade = market ? (r.nextBoolean() ? Validade.IOC : Validade.FOK)
                    : Validade.values()[r.nextInt(10) < 7 ? 0 : 1 + r.nextInt(2)];
            // Preços em ticks dentro de ±12% da referência (inclui fora da faixa da Market).
            long ticks = p.referencia().divide(p.tick()).longValue();
            long desvio = Math.round(ticks * 0.12);
            BigDecimal preco = p.tick().multiply(BigDecimal.valueOf(ticks - desvio + r.nextInt((int) (2 * desvio + 1))));
            BigDecimal limite = market ? null : preco;
            BigDecimal maximo = lado == Lado.VENDA ? null
                    : market ? p.tick().multiply(BigDecimal.valueOf(ticks + r.nextInt((int) desvio + 1))) : preco;
            // Reentrega ocasional da mesma ordem.
            if (!lista.isEmpty() && r.nextInt(40) == 0) {
                lista.add(lista.get(r.nextInt(lista.size())));
                continue;
            }
            lista.add(new Admissao(UUID.nameUUIDFromBytes(("o" + semente + "-" + i).getBytes()),
                    UUID.nameUUIDFromBytes(("i" + semente + "-" + i).getBytes()), "CONTA-00" + (1 + r.nextInt(3)),
                    ticker, lado, market ? TipoOrdem.MARKET : TipoOrdem.LIMIT, validade, 1 + r.nextInt(300), limite,
                    maximo, sessao));
        }
        return lista;
    }

    /** Oferta viva no modelo: preço, sequência de chegada e restante. */
    record Viva(Lado lado, BigDecimal preco, long sequencia, long restante) {
    }

    @Test
    void invariantesEmSementesFixas() {
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

    static List<Evento> executar(List<Admissao> admissoes) {
        MotorMatching motor = new MotorMatching(ATIVOS);
        List<Evento> todos = new ArrayList<>();
        admissoes.forEach(a -> todos.addAll(motor.processar(a)));
        return todos;
    }

    private static void verificar(List<Admissao> admissoes) {
        MotorMatching motor = new MotorMatching(ATIVOS);
        Map<UUID, Admissao> porOrdem = new HashMap<>();
        Map<UUID, Long> executada = new HashMap<>();
        Map<UUID, Long> sequencia = new HashMap<>();
        Map<String, Map<UUID, Viva>> livro = new HashMap<>();
        Map<UUID, Boolean> encerrada = new HashMap<>();
        for (Admissao a : admissoes) {
            boolean nova = !porOrdem.containsKey(a.ordem());
            porOrdem.putIfAbsent(a.ordem(), a);
            List<Evento> eventos = motor.processar(a);
            if (!nova) {
                assertEquals(1, eventos.size(), "reentrega só é rejeitada");
                assertTrue(eventos.getFirst() instanceof Evento.OrdemRejeitada);
                continue;
            }
            Map<UUID, Viva> doAtivo = livro.computeIfAbsent(a.ticker(), t -> new HashMap<>());
            for (Evento e : eventos) {
                switch (e) {
                    case Evento.OrdemAceita aceita -> sequencia.put(aceita.ordem(), aceita.sequencia());
                    case Evento.Negocio n -> verificarNegocio(n, a, porOrdem, doAtivo, executada);
                    case Evento.OfertaNoLivro o -> {
                        assertEquals(a.ordem(), o.ordem());
                        doAtivo.put(o.ordem(), new Viva(o.lado(), o.preco(), sequencia.get(o.ordem()), o.restante()));
                    }
                    case Evento.OrdemEncerrada fim -> {
                        assertTrue(encerrada.putIfAbsent(fim.ordem(), true) == null, "encerrada duas vezes");
                        Admissao original = porOrdem.get(fim.ordem());
                        assertEquals(original.quantidade(), fim.executada() + fim.cancelada(), "conservação");
                        assertEquals(executada.getOrDefault(fim.ordem(), 0L), fim.executada(), "executada");
                        doAtivo.remove(fim.ordem());
                    }
                    case Evento.OrdemRejeitada ignorada -> {
                    }
                }
            }
            // Ordem aceita termina encerrada ou repousando, nunca as duas coisas.
            if (sequencia.containsKey(a.ordem())) {
                assertTrue(encerrada.containsKey(a.ordem()) != doAtivo.containsKey(a.ordem()), "destino da ordem");
            }
            naoCruzado(doAtivo);
        }
    }

    private static void verificarNegocio(Evento.Negocio n, Admissao agressora, Map<UUID, Admissao> porOrdem,
                                         Map<UUID, Viva> livro, Map<UUID, Long> executada) {
        UUID passivaId = agressora.lado() == Lado.COMPRA ? n.ordemVenda() : n.ordemCompra();
        Viva passiva = livro.get(passivaId);
        assertTrue(passiva != null, "negócio contra oferta fora do livro");
        assertEquals(0, passiva.preco().compareTo(n.preco()), "preço passivo");
        // FIFO e preço-tempo: nenhuma oferta do mesmo lado com preço melhor, ou igual e chegada anterior.
        for (Viva outra : livro.values()) {
            if (outra.lado() != passiva.lado() || outra == passiva) {
                continue;
            }
            int c = outra.preco().compareTo(passiva.preco());
            boolean melhor = passiva.lado() == Lado.VENDA ? c < 0 : c > 0;
            assertTrue(!melhor && !(c == 0 && outra.sequencia() < passiva.sequencia()), "prioridade preço-tempo");
        }
        if (agressora.lado() == Lado.COMPRA) {
            assertTrue(n.preco().compareTo(agressora.precoMaximo()) <= 0, "compra acima da reserva");
        }
        if (agressora.tipo() == TipoOrdem.LIMIT) {
            int c = n.preco().compareTo(agressora.precoLimite());
            assertTrue(agressora.lado() == Lado.COMPRA ? c <= 0 : c >= 0, "limite do agressor");
        }
        long restante = passiva.restante() - n.quantidade();
        assertTrue(restante >= 0, "quantidade além do saldo passivo");
        livro.put(passivaId, new Viva(passiva.lado(), passiva.preco(), passiva.sequencia(), restante));
        executada.merge(passivaId, n.quantidade(), Long::sum);
        executada.merge(agressora.ordem(), n.quantidade(), Long::sum);
        assertTrue(n.id().startsWith(agressora.ticker() + "-2026-10-19-"));
        assertTrue(porOrdem.containsKey(passivaId));
    }

    private static void naoCruzado(Map<UUID, Viva> livro) {
        TreeMap<BigDecimal, Long> compras = new TreeMap<>();
        TreeMap<BigDecimal, Long> vendas = new TreeMap<>();
        for (Viva v : livro.values()) {
            assertTrue(v.restante() > 0, "oferta vazia no livro");
            (v.lado() == Lado.COMPRA ? compras : vendas).merge(v.preco(), v.restante(), Long::sum);
        }
        if (!compras.isEmpty() && !vendas.isEmpty()) {
            assertTrue(compras.lastKey().compareTo(vendas.firstKey()) < 0, "livro cruzado");
        }
    }
}
