package br.prospero.bo.razao;

import br.prospero.bo.posicoes.dominio.EventoMatching;
import br.prospero.bo.posicoes.dominio.ProjecaoIncoerenteException;
import br.prospero.bo.razao.dominio.*;
import br.prospero.bo.razao.helper.PlanoDeContas;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static br.prospero.bo.razao.dominio.ContaContabil.*;
import static br.prospero.bo.razao.dominio.Natureza.CREDITO;
import static br.prospero.bo.razao.dominio.Natureza.DEBITO;
import static org.junit.jupiter.api.Assertions.*;

class PlanoDeContasTest {
    private static final LocalDate SESSAO = LocalDate.of(2026, 10, 23);
    private static final List<String> ATIVOS = List.of("ALFA3", "BETA3", "GAMA3", "DELTA3", "OMEGA3");
    private static final List<String> CONTAS = List.of("CONTA-001", "CONTA-002", "CONTA-003", "CONTA-004");

    @Test
    void exemploConfirmadoPeloUsuarioGeraOsQuatroLancamentosD0() {
        List<TransacaoContabil> d0 = PlanoDeContas.d0(fato("ALFA3", "20.00", 100, "CONTA-001", "CONTA-002"));
        assertEquals(2, d0.size());
        TransacaoContabil comprador = d0.get(0);
        assertEquals(LadoNegocio.COMPRADOR, comprador.lado());
        assertEquals("CONTA-001", comprador.conta());
        assertEquals(Momento.D0, comprador.momento());
        assertEquals(List.of(
                new Partida(CAMARA, DEBITO, Montante.brl(new BigDecimal("2000.00"))),
                new Partida(CAIXA, CREDITO, Montante.brl(new BigDecimal("2000.00"))),
                new Partida(CUSTODIA_A_RECEBER, DEBITO, Montante.ativo("ALFA3", 100)),
                new Partida(CAMARA, CREDITO, Montante.ativo("ALFA3", 100))), comprador.partidas());
        TransacaoContabil vendedor = d0.get(1);
        assertEquals(LadoNegocio.VENDEDOR, vendedor.lado());
        assertEquals("CONTA-002", vendedor.conta());
        assertEquals(List.of(
                new Partida(CAMARA, DEBITO, Montante.ativo("ALFA3", 100)),
                new Partida(CUSTODIA, CREDITO, Montante.ativo("ALFA3", 100)),
                new Partida(CAIXA_A_RECEBER, DEBITO, Montante.brl(new BigDecimal("2000.00"))),
                new Partida(CAMARA, CREDITO, Montante.brl(new BigDecimal("2000.00")))), vendedor.partidas());
        assertEquals("2000.00", comprador.partidas().getFirst().montante().texto());
        assertEquals("100", vendedor.partidas().getFirst().montante().texto());
        assertNotEquals(comprador.id(), vendedor.id());
        assertEquals(comprador.id(), PlanoDeContas.d0(fato("ALFA3", "20.00", 100, "CONTA-001", "CONTA-002")).getFirst().id());
    }

    @Test
    void vendaADescobertoDeixaCustodiaNegativaNoD2AteARecompraComRazaoBalanceado() {
        // ADR-021: CONTA-002 vende 100 ALFA3 sem posição anterior e recompra 100 depois.
        FatoNegocio venda = fato("ALFA3", "20.00", 100, "CONTA-001", "CONTA-002");
        var c = new EventoMatching.Alvo(UUID.randomUUID(), UUID.randomUUID(), "CONTA-002");
        var v = new EventoMatching.Alvo(UUID.randomUUID(), UUID.randomUUID(), "CONTA-003");
        FatoNegocio recompra = new FatoNegocio("ALFA3-" + SESSAO + "-2", "ALFA3", SESSAO, SESSAO.plusDays(4),
                new BigDecimal("18.00"), 100, c, v);
        List<TransacaoContabil> razao = new ArrayList<>();
        for (FatoNegocio fato : List.of(venda, recompra)) {
            razao.addAll(PlanoDeContas.d0(fato));
            razao.add(PlanoDeContas.d2(fato.negocio(), LadoNegocio.COMPRADOR, fato.compra().conta(), fato.ativo()));
            razao.add(PlanoDeContas.d2(fato.negocio(), LadoNegocio.VENDEDOR, fato.venda().conta(), fato.financeiro()));
        }
        for (TransacaoContabil t : razao) {
            for (String unidade : List.of("BRL", "ALFA3")) {
                assertEquals(0, soma(t, unidade, DEBITO).compareTo(soma(t, unidade, CREDITO)), t.id() + " " + unidade);
            }
        }
        Map<String, BigDecimal> aposVenda = new TreeMap<>();
        razao.subList(0, 4).forEach(t -> acumular(aposVenda, t));
        assertEquals(new BigDecimal("-100"), aposVenda.get("CONTA-002|CUSTODIA|ALFA3"), "custódia a entregar após o D+2");
        assertNull(aposVenda.get("CONTA-002|CUSTODIA_A_RECEBER|ALFA3"));
        assertEquals(new BigDecimal("2000.00"), aposVenda.get("CONTA-002|CAIXA|BRL"));
        assertEquals(0, aposVenda.get("CONTA-002|CAIXA_A_RECEBER|BRL").signum(), "caixa liquidado no D+2");
        Map<String, BigDecimal> aposRecompra = new TreeMap<>();
        razao.forEach(t -> acumular(aposRecompra, t));
        assertEquals(0, aposRecompra.get("CONTA-002|CUSTODIA|ALFA3").signum(), "recompra zera a custódia a entregar");
        assertEquals(new BigDecimal("200.00"), aposRecompra.get("CONTA-002|CAIXA|BRL"), "resultado de caixa da operação");
        assertEquals(0, total(aposRecompra, CAMARA, "BRL").signum());
        assertEquals(0, total(aposRecompra, CAMARA, "ALFA3").signum());
    }

    @Test
    void autonegociacaoGeraOsDoisLadosParaAMesmaConta() {
        List<TransacaoContabil> d0 = PlanoDeContas.d0(fato("DELTA3", "40.00", 1, "CONTA-002", "CONTA-002"));
        assertEquals(List.of(LadoNegocio.COMPRADOR, LadoNegocio.VENDEDOR), d0.stream().map(TransacaoContabil::lado).toList());
        assertTrue(d0.stream().allMatch(t -> t.conta().equals("CONTA-002")));
        Map<String, BigDecimal> saldo = new TreeMap<>();
        d0.forEach(t -> acumular(saldo, t));
        // Entre D0 e D+2: caixa e custódia debitados e os mesmos valores a receber (sem day trade).
        assertEquals(new BigDecimal("-40.00"), saldo.get("CONTA-002|CAIXA|BRL"));
        assertEquals(new BigDecimal("40.00"), saldo.get("CONTA-002|CAIXA_A_RECEBER|BRL"));
        assertEquals(new BigDecimal("-1"), saldo.get("CONTA-002|CUSTODIA|DELTA3"));
        assertEquals(new BigDecimal("1"), saldo.get("CONTA-002|CUSTODIA_A_RECEBER|DELTA3"));
        assertEquals(0, saldo.get("CONTA-002|CAMARA|BRL").signum());
        assertEquals(0, saldo.get("CONTA-002|CAMARA|DELTA3").signum());
    }

    @Test
    void transacaoDesbalanceadaOuMontanteForaDaEscalaSaoRecusados() {
        var brl = Montante.brl(new BigDecimal("1.00"));
        var erro = assertThrows(ProjecaoIncoerenteException.class, () -> new TransacaoContabil("ALFA3-2026-10-23-1",
                LadoNegocio.COMPRADOR, "CONTA-001", Momento.D0, List.of(new Partida(CAMARA, DEBITO, brl),
                new Partida(CAIXA, CREDITO, Montante.brl(new BigDecimal("1.01"))))));
        assertEquals("RAZAO_DESBALANCEADA", erro.getMessage());
        assertThrows(ProjecaoIncoerenteException.class, () -> new TransacaoContabil("ALFA3-2026-10-23-1",
                LadoNegocio.COMPRADOR, "CONTA-001", Momento.D0, List.of(new Partida(CAMARA, DEBITO, brl),
                new Partida(CAIXA, CREDITO, Montante.ativo("ALFA3", 1)))), "unidades distintas não se compensam");
        assertThrows(ProjecaoIncoerenteException.class, () -> new TransacaoContabil("ALFA3-2026-10-23-1",
                LadoNegocio.COMPRADOR, "CONTA-001", Momento.D0, List.of()));
        for (String invalido : new String[]{"1.0", "1.001", "0.00", "-1.00"}) {
            assertThrows(IllegalArgumentException.class, () -> Montante.brl(new BigDecimal(invalido)), invalido);
        }
        assertThrows(IllegalArgumentException.class, () -> Montante.ativo("ALFA3", 0));
        assertThrows(IllegalArgumentException.class, () -> Montante.ativo("XPTO3", 1));
        assertThrows(IllegalArgumentException.class, () -> new Montante("ALFA3", new BigDecimal("1.5")));
        assertThrows(IllegalArgumentException.class, () -> Montante.brl(new BigDecimal("10000000000000.00")));
    }

    @Test
    void valorDoNegocioEExatoEmCentavosSemDouble() {
        FatoNegocio fato = fato("OMEGA3", "999999.95", 1_000_000, "CONTA-003", "CONTA-004");
        assertEquals(new BigDecimal("999999950000.00"), fato.valor());
        assertEquals(2, fato.valor().scale());
        assertThrows(IllegalArgumentException.class, () -> fato("ALFA3", "20.001", 1, "CONTA-001", "CONTA-002"));
        assertThrows(IllegalArgumentException.class, () -> fato("ALFA3", "20.00", 0, "CONTA-001", "CONTA-002"));
    }

    @Test
    void balancoPorTransacaoEUnidadeEConservacaoEntreParticipantesEmSequenciasDeterministicas() {
        for (int semente = 0; semente < 100; semente++) {
            Random aleatorio = new Random(semente);
            Map<String, BigDecimal> saldo = new TreeMap<>();
            Map<String, Long> centavosEsperados = new HashMap<>();
            for (int passo = 0; passo < 100; passo++) {
                String ticker = ATIVOS.get(aleatorio.nextInt(ATIVOS.size()));
                long centavos = 1 + aleatorio.nextInt(10_000_000);
                long quantidade = 1 + aleatorio.nextInt(1_000);
                String compra = CONTAS.get(aleatorio.nextInt(CONTAS.size()));
                // Um quarto dos negócios é autonegociação.
                String venda = aleatorio.nextInt(4) == 0 ? compra : CONTAS.get(aleatorio.nextInt(CONTAS.size()));
                FatoNegocio fato = fato(ticker, BigDecimal.valueOf(centavos, 2).toPlainString(), quantidade, compra, venda);
                List<TransacaoContabil> d0 = PlanoDeContas.d0(fato);
                assertEquals(2, d0.size(), semente + "/" + passo);
                for (TransacaoContabil t : d0) {
                    for (String unidade : List.of("BRL", ticker)) {
                        assertEquals(0, soma(t, unidade, DEBITO).compareTo(soma(t, unidade, CREDITO)),
                                "balanço: " + semente + "/" + passo + "/" + unidade);
                    }
                    acumular(saldo, t);
                }
                // Valor exato por aritmética inteira independente (centavos × quantidade).
                assertEquals(BigDecimal.valueOf(Math.multiplyExact(centavos, quantidade), 2), fato.valor());
                centavosEsperados.merge(compra, -Math.multiplyExact(centavos, quantidade), Long::sum);
                centavosEsperados.merge(venda, Math.multiplyExact(centavos, quantidade), Long::sum);
            }
            // Câmara fecha em zero por unidade somando todas as contas; caixa e custódia se conservam entre participantes.
            for (String unidade : union("BRL", ATIVOS)) {
                assertEquals(0, total(saldo, CAMARA, unidade).signum(), "câmara " + semente + "/" + unidade);
                assertEquals(0, total(saldo, CAIXA, unidade).add(total(saldo, CAIXA_A_RECEBER, unidade)).signum(),
                        "caixa " + semente + "/" + unidade);
                assertEquals(0, total(saldo, CUSTODIA, unidade).add(total(saldo, CUSTODIA_A_RECEBER, unidade)).signum(),
                        "custódia " + semente + "/" + unidade);
            }
            for (String conta : CONTAS) {
                BigDecimal liquido = saldo.getOrDefault(conta + "|CAIXA|BRL", BigDecimal.ZERO)
                        .add(saldo.getOrDefault(conta + "|CAIXA_A_RECEBER|BRL", BigDecimal.ZERO));
                assertEquals(BigDecimal.valueOf(centavosEsperados.getOrDefault(conta, 0L), 2).compareTo(liquido), 0,
                        "caixa líquido após D+2 " + semente + "/" + conta);
            }
        }
    }

    static FatoNegocio fato(String ticker, String preco, long quantidade, String compra, String venda) {
        var c = new EventoMatching.Alvo(UUID.randomUUID(), UUID.randomUUID(), compra);
        var v = new EventoMatching.Alvo(UUID.randomUUID(), UUID.randomUUID(), venda);
        return new FatoNegocio(ticker + "-" + SESSAO + "-1", ticker, SESSAO, SESSAO.plusDays(4), new BigDecimal(preco),
                quantidade, c, v);
    }

    private static BigDecimal soma(TransacaoContabil t, String unidade, Natureza natureza) {
        return t.partidas().stream().filter(p -> p.natureza() == natureza && p.montante().unidade().equals(unidade))
                .map(p -> p.montante().valor()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Saldo devedor positivo: débito soma, crédito subtrai. */
    private static void acumular(Map<String, BigDecimal> saldo, TransacaoContabil t) {
        for (Partida p : t.partidas()) {
            BigDecimal valor = p.natureza() == DEBITO ? p.montante().valor() : p.montante().valor().negate();
            saldo.merge(t.conta() + "|" + p.conta() + "|" + p.montante().unidade(), valor, BigDecimal::add);
        }
    }

    private static BigDecimal total(Map<String, BigDecimal> saldo, ContaContabil conta, String unidade) {
        return saldo.entrySet().stream().filter(e -> e.getKey().endsWith("|" + conta + "|" + unidade))
                .map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<String> union(String primeiro, List<String> resto) {
        List<String> todos = new ArrayList<>(List.of(primeiro));
        todos.addAll(resto);
        return todos;
    }
}
