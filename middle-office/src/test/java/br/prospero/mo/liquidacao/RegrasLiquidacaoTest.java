package br.prospero.mo.liquidacao;

import br.prospero.mo.liquidacao.dominio.CreditoEsperado;
import br.prospero.mo.liquidacao.dominio.Desfecho;
import br.prospero.mo.liquidacao.dominio.EventoLiquidacao;
import br.prospero.mo.liquidacao.dominio.Expectativa;
import br.prospero.mo.liquidacao.dominio.LadoLiquidacao;
import br.prospero.mo.liquidacao.dominio.LiquidacaoAguardando;
import br.prospero.mo.liquidacao.dominio.MotivoRecusa;
import br.prospero.mo.liquidacao.dominio.RecursoCredito;
import br.prospero.mo.liquidacao.dominio.RegrasLiquidacao;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RF08c: regras puras de expectativa, crédito, aguardando e recusa (ADR-016). */
class RegrasLiquidacaoTest {
    private static final String NEGOCIO = "ALFA3-2026-10-18-1";
    private static final CreditoEsperado CAIXA = new CreditoEsperado(NEGOCIO, LadoLiquidacao.VENDEDOR, "CONTA-002", "ALFA3",
            new BigDecimal("2000.00"));
    private static final EventoLiquidacao EVENTO = new EventoLiquidacao(CAIXA.id(), "a".repeat(64), UUID.randomUUID(), CAIXA);

    @Test
    void negocioDoExemploDoAdrGeraCustodiaAoCompradorECaixaExatoAoVendedor() {
        var esperados = CreditoEsperado.doNegocio(NEGOCIO, "ALFA3", "CONTA-001", "CONTA-002", new BigDecimal("20.00"), 100);
        assertEquals(2, esperados.size());
        var comprador = esperados.get(0);
        var vendedor = esperados.get(1);
        assertEquals(LadoLiquidacao.COMPRADOR, comprador.lado());
        assertEquals(RecursoCredito.CUSTODIA, comprador.recurso());
        assertEquals("CONTA-001", comprador.conta());
        assertEquals(new BigDecimal("100"), comprador.valor());
        assertEquals(RecursoCredito.CAIXA, vendedor.recurso());
        assertEquals("CONTA-002", vendedor.conta());
        assertEquals(new BigDecimal("2000.00"), vendedor.valor());
        assertEquals(new BigDecimal("0.03"),
                CreditoEsperado.doNegocio(NEGOCIO, "ALFA3", "CONTA-001", "CONTA-002", new BigDecimal("0.01"), 3).get(1).valor());
    }

    @Test
    void autonegociacaoGeraOsDoisLadosParaAMesmaConta() {
        var esperados = CreditoEsperado.doNegocio(NEGOCIO, "ALFA3", "CONTA-004", "CONTA-004", new BigDecimal("9.99"), 7);
        assertEquals(List.of(LadoLiquidacao.COMPRADOR, LadoLiquidacao.VENDEDOR), esperados.stream().map(CreditoEsperado::lado).toList());
        assertTrue(esperados.stream().allMatch(c -> c.conta().equals("CONTA-004")));
        assertNotEquals(esperados.get(0).id(), esperados.get(1).id());
        assertEquals(new BigDecimal("69.93"), esperados.get(1).valor());
    }

    @Test
    void idDerivadoEDeterministicoEDocumentado() {
        UUID esperado = UUID.nameUUIDFromBytes(("liquidacao:" + NEGOCIO + ":VENDEDOR").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(esperado, CreditoEsperado.id(NEGOCIO, LadoLiquidacao.VENDEDOR));
        assertEquals(esperado, CAIXA.id());
        assertEquals(3, esperado.version());
        assertNotEquals(esperado, CreditoEsperado.id(NEGOCIO, LadoLiquidacao.COMPRADOR));
    }

    @Test
    void valorExatoSemArredondamentoNemDouble() {
        assertEquals(CAIXA, new CreditoEsperado(NEGOCIO, LadoLiquidacao.VENDEDOR, "CONTA-002", "ALFA3", new BigDecimal("2000")));
        assertThrows(ArithmeticException.class, () ->
                new CreditoEsperado(NEGOCIO, LadoLiquidacao.VENDEDOR, "CONTA-002", "ALFA3", new BigDecimal("0.001")));
        assertThrows(ArithmeticException.class, () ->
                new CreditoEsperado(NEGOCIO, LadoLiquidacao.COMPRADOR, "CONTA-002", "ALFA3", new BigDecimal("1.5")));
        assertThrows(IllegalArgumentException.class, () ->
                new CreditoEsperado(NEGOCIO, LadoLiquidacao.COMPRADOR, "CONTA-002", "ALFA3", BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                new CreditoEsperado(NEGOCIO, LadoLiquidacao.COMPRADOR, "CONTA-002", "ALFA3", BigDecimal.valueOf(1_000_001)));
    }

    private static Desfecho desfecho(Optional<Expectativa> expectativa, Optional<String> aplicada, boolean jaAguardando,
                                     long aguardando, long teto) {
        return RegrasLiquidacao.desfecho(EVENTO, expectativa, aplicada, jaAguardando, () -> aguardando, teto);
    }

    @Test
    void expectativaIdenticaCreditaEReentregaIdenticaNaoTemEfeito() {
        assertEquals(Desfecho.CREDITAR, desfecho(Optional.of(new Expectativa(CAIXA, false)), Optional.empty(), false, 0, 10));
        assertEquals(Desfecho.REPETICAO, desfecho(Optional.of(new Expectativa(CAIXA, true)), Optional.of(EVENTO.assinatura()), false, 0, 10));
    }

    @Test
    void mesmoIdComAssinaturaDiferenteDepoisDoCreditoERecusado() {
        assertEquals(Desfecho.recusar(MotivoRecusa.REPETICAO_DIVERGENTE),
                desfecho(Optional.of(new Expectativa(CAIXA, true)), Optional.of("b".repeat(64)), false, 0, 10));
    }

    @Test
    void divergenciaDeContaRecursoAtivoOuValorERecusadaSemCredito() {
        for (CreditoEsperado outro : List.of(
                new CreditoEsperado(NEGOCIO, LadoLiquidacao.VENDEDOR, "CONTA-003", "ALFA3", new BigDecimal("2000.00")),
                new CreditoEsperado(NEGOCIO, LadoLiquidacao.VENDEDOR, "CONTA-002", "BETA3", new BigDecimal("2000.00")),
                new CreditoEsperado(NEGOCIO, LadoLiquidacao.VENDEDOR, "CONTA-002", "ALFA3", new BigDecimal("2000.01")))) {
            assertEquals(Desfecho.recusar(MotivoRecusa.DIVERGENTE),
                    desfecho(Optional.of(new Expectativa(outro, false)), Optional.empty(), false, 0, 10));
        }
    }

    @Test
    void semExpectativaAguardaAteOTetoERepeticaoAguardandoNaoOcupaOutraVaga() {
        assertEquals(Desfecho.AGUARDAR, desfecho(Optional.empty(), Optional.empty(), false, 9, 10));
        assertEquals(Desfecho.recusar(MotivoRecusa.AGUARDANDO_ESGOTADO), desfecho(Optional.empty(), Optional.empty(), false, 10, 10));
        assertEquals(Desfecho.REPETICAO, desfecho(Optional.empty(), Optional.empty(), true, 10, 10));
    }

    @Test
    void contagemDoAguardandoSoEConsultadaSemExpectativa() {
        assertEquals(Desfecho.CREDITAR, RegrasLiquidacao.desfecho(EVENTO, Optional.of(new Expectativa(CAIXA, false)),
                Optional.empty(), false, () -> { throw new AssertionError("contagem desnecessária"); }, 10));
    }

    private static LiquidacaoAguardando aguardando(CreditoEsperado credito, char assinatura) {
        return new LiquidacaoAguardando(credito.id(), String.valueOf(assinatura).repeat(64), credito, 0, assinatura);
    }

    @Test
    void expectativaAplicaOPrimeiroAguardandoIdenticoERecusaDivergentesDoMesmoId() {
        var forjado = aguardando(new CreditoEsperado(NEGOCIO, LadoLiquidacao.VENDEDOR, "CONTA-003", "ALFA3", new BigDecimal("2000.00")), 'f');
        var legitimo = aguardando(CAIXA, 'c');
        var copia = aguardando(CAIXA, 'd');
        var resolucao = RegrasLiquidacao.resolver(CAIXA, List.of(forjado, legitimo, copia));
        assertEquals(Optional.of(legitimo), resolucao.aplicar());
        assertEquals(List.of(new RegrasLiquidacao.Recusa(forjado, MotivoRecusa.DIVERGENTE),
                new RegrasLiquidacao.Recusa(copia, MotivoRecusa.REPETICAO_DIVERGENTE)), resolucao.recusar());
    }

    @Test
    void semAguardandoIdenticoNadaEAplicado() {
        var forjado = aguardando(new CreditoEsperado(NEGOCIO, LadoLiquidacao.VENDEDOR, "CONTA-002", "ALFA3", new BigDecimal("1.00")), 'f');
        var resolucao = RegrasLiquidacao.resolver(CAIXA, List.of(forjado));
        assertTrue(resolucao.aplicar().isEmpty());
        assertEquals(List.of(new RegrasLiquidacao.Recusa(forjado, MotivoRecusa.DIVERGENTE)), resolucao.recusar());
        assertTrue(RegrasLiquidacao.resolver(CAIXA, List.of()).aplicar().isEmpty());
    }
}
