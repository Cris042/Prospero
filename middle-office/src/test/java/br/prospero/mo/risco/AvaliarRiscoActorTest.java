package br.prospero.mo.risco;

import br.prospero.mo.mensageria.dominio.MensagemSaida;
import br.prospero.mo.risco.actor.AvaliarRiscoActor;
import br.prospero.mo.risco.dominio.Carteira;
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
    void comandoV2ReservaEAdmiteSomenteUmaVez() {
        var antiga = compra("CONTA-002", 7, "10.00");
        var p = antiga.pernas().getFirst();
        var nova = new ComandoRisco(antiga.evento(), antiga.intencao(), antiga.conta(), antiga.ticker(), antiga.lado(),
                antiga.precoBase(), List.of(new br.prospero.mo.risco.dominio.Perna(p.id(), p.tipo(), p.quantidade(),
                p.precoLimite(), null, br.prospero.mo.risco.dominio.Validade.DIA)), null, java.time.LocalDate.of(2026,10,18));
        actor.avaliar(nova, null);
        actor.avaliar(nova, null);
        assertEquals(2, outbox.size());
        assertEquals(1, outbox.stream().filter(m -> m.topico().equals("matching.comandos.v1")).count());
        assertTrue(outbox.getFirst().payload().contains("\"precoMaximo\":\"10.00\""));
    }

    @Test
    void parOcoReservadoEAdmitidoEmV3ComPernasNaOrdemEPrecoMaximoComum() throws Exception {
        var dia = br.prospero.mo.risco.dominio.Validade.DIA;
        var tp = new br.prospero.mo.risco.dominio.Perna(UUID.randomUUID(), TipoOrdem.LIMIT, 2, new BigDecimal("9.80"), null, dia);
        var sl = new br.prospero.mo.risco.dominio.Perna(UUID.randomUUID(), TipoOrdem.STOP, 2, null, new BigDecimal("10.20"), dia);
        var par = new ComandoRisco(UUID.randomUUID(), UUID.randomUUID(), "CONTA-002", "ALFA3", Lado.COMPRA,
                new BigDecimal("10.00"), List.of(tp, sl), null, java.time.LocalDate.of(2026, 10, 18));
        assertTrue(par.admissivel());
        actor.avaliar(par, null);
        var admissao = outbox.stream().filter(m -> m.topico().equals("matching.comandos.v1")).toList();
        assertEquals(1, admissao.size());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(admissao.getFirst().payload());
        assertEquals(3, json.get("versao").intValue());
        var dados = json.get("dados");
        // Teto do Stop sobre o gatilho 10,20 com túnel de 10%: 11,22, maior que o alvo 9,80.
        assertEquals("11.22", dados.get("precoMaximo").textValue());
        assertEquals("TAKE_PROFIT", dados.get("pernas").get(0).get("papel").textValue());
        assertEquals(tp.id().toString(), dados.get("pernas").get(0).get("ordem").textValue());
        assertEquals("STOP_LOSS", dados.get("pernas").get(1).get("papel").textValue());
        assertEquals("10.20", dados.get("pernas").get(1).get("precoGatilho").textValue());
        assertTrue(dados.get("pernas").get(1).get("precoLimite").isNull());
        var legado = new ComandoRisco(par.evento(), par.intencao(), par.conta(), par.ticker(), par.lado(), par.precoBase(),
                par.pernas(), null);
        assertFalse(legado.admissivel(), "risco v1 sem sessão continua sem admissão");
        var invertido = new ComandoRisco(par.evento(), par.intencao(), par.conta(), par.ticker(), par.lado(), par.precoBase(),
                List.of(sl, tp), null, par.dataSessao());
        assertFalse(invertido.admissivel());
        var alvoAcimaDoGatilho = new br.prospero.mo.risco.dominio.Perna(tp.id(), TipoOrdem.LIMIT, 2,
                new BigDecimal("10.30"), null, dia);
        assertFalse(new ComandoRisco(par.evento(), par.intencao(), par.conta(), par.ticker(), par.lado(), par.precoBase(),
                List.of(alvoAcimaDoGatilho, sl), null, par.dataSessao()).admissivel(), "compra exige alvo abaixo do gatilho");
        var stopLimitIncoerente = new br.prospero.mo.risco.dominio.Perna(sl.id(), TipoOrdem.STOP_LIMIT, 2,
                new BigDecimal("10.10"), new BigDecimal("10.20"), dia);
        assertFalse(new ComandoRisco(par.evento(), par.intencao(), par.conta(), par.ticker(), par.lado(), par.precoBase(),
                List.of(tp, stopLimitIncoerente), null, par.dataSessao()).admissivel(), "Stop Limit de compra abaixo do gatilho");
    }

    @Test
    void preparacaoRecusaReservaLegadaSemAlterarCarteira() {
        var comando=compra("CONTA-002",7,"10.00");
        actor.avaliar(comando,null);
        var antes=repositorio.carteira("CONTA-002").orElseThrow();
        var erro=org.junit.jupiter.api.Assertions.assertThrows(br.prospero.mo.comum.dominio.RegraNegocioException.class,
                ()->repositorio.preparar("CONTA-002",new BigDecimal("1000.00"),Map.of()));
        assertEquals("RESERVA_VIVA",erro.codigo());
        assertEquals(antes,repositorio.carteira("CONTA-002").orElseThrow());
        assertEquals(1,repositorio.decisoes.size());
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
        @Override public void bloquearConta(String conta) { }
        final Map<UUID, ComandoRisco> admissoes = new HashMap<>();
        @Override public void registrarAdmissao(ComandoRisco comando, Decisao decisao) {
            admissoes.put(comando.intencao(), comando);
        }
        final Map<String, BigDecimal> disponivel = new HashMap<>(Map.of("CONTA-002", new BigDecimal("100.00"),
                "CONTA-003", new BigDecimal("100.00")));
        final Map<String, Long> custodia = new HashMap<>(Map.of("CONTA-002|ALFA3", 1000L));
        final Map<UUID, Decisao> decisoes = new HashMap<>();
        final Map<String, BigDecimal> totais = new HashMap<>();
        final Map<String, Long> custodiaTotal = new HashMap<>();

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
        public synchronized boolean reservarCaixa(String conta, BigDecimal valor) {
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
        public synchronized Optional<Carteira> carteira(String conta) {
            BigDecimal livre = disponivel.get(conta);
            if (livre == null) {
                return Optional.empty();
            }
            BigDecimal total = totais.getOrDefault(conta, new BigDecimal("100.00"));
            List<Carteira.Custodia> custodias = custodia.entrySet().stream().filter(e -> e.getKey().startsWith(conta + "|"))
                    .map(e -> new Carteira.Custodia(e.getKey().substring(conta.length() + 1),
                            custodiaTotal.getOrDefault(e.getKey(), 1000L), e.getValue(),
                            custodiaTotal.getOrDefault(e.getKey(), 1000L) - e.getValue())).toList();
            return Optional.of(new Carteira(conta, total, livre, total.subtract(livre), custodias));
        }

        @Override
        public synchronized void preparar(String conta, BigDecimal saldo, Map<String, Long> quantidades) {
            if (decisoes.values().stream().anyMatch(d -> d.conta().equals(conta) && d.resultado() == ResultadoRisco.RESERVADA)) {
                throw new br.prospero.mo.comum.dominio.RegraNegocioException(br.prospero.mo.comum.dominio.Categoria.CONFLITO,
                        "RESERVA_VIVA", "Conta possui reserva ativa.");
            }
            disponivel.put(conta, saldo);
            totais.put(conta, saldo);
            custodia.put(conta + "|ALFA3", quantidades.getOrDefault("ALFA3", 0L));
            custodiaTotal.put(conta + "|ALFA3", quantidades.getOrDefault("ALFA3", 0L));
        }

        @Override
        public void registrar(Decisao decisao) {
            if (decisoes.putIfAbsent(decisao.intencao(), decisao) != null) {
                throw new IllegalStateException("intenção duplicada");
            }
        }
    }
}
