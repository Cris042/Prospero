package br.prospero.mo.risco;

import br.prospero.mo.mensageria.dominio.MensagemSaida;
import br.prospero.mo.risco.actor.AvaliarRiscoActor;
import br.prospero.mo.risco.dominio.ComandoRisco;
import br.prospero.mo.risco.dominio.Decisao;
import br.prospero.mo.risco.dominio.Lado;
import br.prospero.mo.risco.dominio.MotivoRejeicao;
import br.prospero.mo.risco.dominio.ParametrosRisco;
import br.prospero.mo.risco.dominio.RepositorioRisco;
import br.prospero.mo.risco.dominio.ResultadoRisco;
import br.prospero.mo.risco.dominio.TipoOrdem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static br.prospero.mo.risco.CalculoReservaTest.perna;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Caso de uso com repositório em memória: decisão, reserva, recusa sem efeito e reentrega idempotente. */
class AvaliarRiscoActorTest {
    private final Memoria repositorio = new Memoria();
    private final List<MensagemSaida> outbox = new ArrayList<>();
    private AvaliarRiscoActor actor;

    @BeforeEach
    void preparar() {
        actor = new AvaliarRiscoActor(repositorio, outbox::add,
                Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC), new SimpleMeterRegistry());
    }

    static ComandoRisco compra(String conta, long quantidade, String limite) {
        return new ComandoRisco(UUID.randomUUID(), UUID.randomUUID(), conta, "ALFA3", Lado.COMPRA, new BigDecimal("10.00"),
                List.of(perna(TipoOrdem.LIMIT, quantidade, limite, null)), null);
    }

    static ComandoRisco venda(long quantidade) {
        return new ComandoRisco(UUID.randomUUID(), UUID.randomUUID(), "CONTA-002", "ALFA3", Lado.VENDA, new BigDecimal("10.00"),
                List.of(perna(TipoOrdem.LIMIT, quantidade, "10.00", null)), null);
    }

    @Test
    void reservaCaixaEPublicaResultado() {
        Decisao d = actor.avaliar(compra("CONTA-002", 7, "10.00"), null).decisao();
        assertEquals(ResultadoRisco.RESERVADA, d.resultado());
        assertEquals(new BigDecimal("70.00"), d.valorReservado());
        assertEquals(new BigDecimal("30.00"), repositorio.disponivel.get("CONTA-002"));
        assertEquals(1, outbox.size());
        assertEquals("CONTA-002", outbox.getFirst().chave());
        assertTrue(outbox.getFirst().payload().contains("\"resultado\":\"RESERVADA\""));
    }

    @Test
    void duasOrdensDe70SobreDisponivel100ReservamUmaSo() {
        assertEquals(ResultadoRisco.RESERVADA, actor.avaliar(compra("CONTA-002", 7, "10.00"), null).decisao().resultado());
        Decisao segunda = actor.avaliar(compra("CONTA-002", 7, "10.00"), null).decisao();
        assertEquals(MotivoRejeicao.SALDO_INSUFICIENTE, segunda.motivo());
        assertEquals(new BigDecimal("30.00"), repositorio.disponivel.get("CONTA-002"), "recusa não altera saldo");
        assertEquals(2, outbox.size(), "rejeição também é publicada");
    }

    @Test
    void reentregaDaMesmaIntencaoNaoReservaNemPublicaDeNovo() {
        ComandoRisco c = compra("CONTA-002", 5, "10.00");
        assertFalse(actor.avaliar(c, null).repetida());
        ComandoRisco reentrega = new ComandoRisco(UUID.randomUUID(), c.intencao(), c.conta(), c.ticker(), c.lado(),
                c.precoBase(), c.pernas(), null);
        AvaliarRiscoActor.Avaliacao segunda = actor.avaliar(reentrega, null);
        assertTrue(segunda.repetida());
        assertEquals(c.evento(), segunda.decisao().evento(), "devolve a decisão original");
        assertEquals(new BigDecimal("50.00"), repositorio.disponivel.get("CONTA-002"));
        assertEquals(1, outbox.size());
    }

    @Test
    void vendaSemCustodiaEContaOuAtivoDesconhecidos() {
        assertEquals(MotivoRejeicao.CUSTODIA_INSUFICIENTE, actor.avaliar(venda(1001), null).decisao().motivo());
        assertEquals(ResultadoRisco.RESERVADA, actor.avaliar(venda(1000), null).decisao().resultado());
        assertEquals(MotivoRejeicao.CUSTODIA_INSUFICIENTE, actor.avaliar(venda(1), null).decisao().motivo());
        assertEquals(MotivoRejeicao.CONTA_DESCONHECIDA,
                actor.avaliar(compra("CONTA-999", 1, "10.00"), null).decisao().motivo());
        ComandoRisco ativo = new ComandoRisco(UUID.randomUUID(), UUID.randomUUID(), "CONTA-002", "ZETA3", Lado.COMPRA,
                null, List.of(perna(TipoOrdem.LIMIT, 1, "1.00", null)), null);
        Decisao d = actor.avaliar(ativo, null).decisao();
        assertEquals(MotivoRejeicao.ATIVO_DESCONHECIDO, d.motivo());
        assertNull(d.versaoParametros());
    }

    /** Mesma semântica do UPDATE condicional: suficiência e mutação juntas. */
    static class Memoria implements RepositorioRisco {
        final Map<String, BigDecimal> disponivel = new HashMap<>(Map.of("CONTA-002", new BigDecimal("100.00")));
        final Map<String, Long> custodia = new HashMap<>(Map.of("CONTA-002|ALFA3", 1000L));
        final Map<UUID, Decisao> decisoes = new HashMap<>();

        @Override
        public Optional<Decisao> decisao(UUID intencao) {
            return Optional.ofNullable(decisoes.get(intencao));
        }

        @Override
        public Optional<ParametrosRisco> parametros(String ticker) {
            return "ALFA3".equals(ticker) ? Optional.of(CalculoReservaTest.ALFA) : Optional.empty();
        }

        @Override
        public boolean contaExiste(String conta) {
            return disponivel.containsKey(conta);
        }

        @Override
        public boolean reservarCaixa(String conta, BigDecimal valor) {
            BigDecimal atual = disponivel.get(conta);
            if (atual.compareTo(valor) < 0) {
                return false;
            }
            disponivel.put(conta, atual.subtract(valor));
            return true;
        }

        @Override
        public boolean reservarCustodia(String conta, String ticker, long quantidade) {
            long atual = custodia.getOrDefault(conta + "|" + ticker, 0L);
            if (atual < quantidade) {
                return false;
            }
            custodia.put(conta + "|" + ticker, atual - quantidade);
            return true;
        }

        @Override
        public void registrar(Decisao decisao) {
            if (decisoes.putIfAbsent(decisao.intencao(), decisao) != null) {
                throw new IllegalStateException("intenção duplicada");
            }
        }
    }
}
