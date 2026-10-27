package br.prospero.fo.cotacao;

import br.prospero.fo.cotacao.dominio.RepositorioCotacao;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;

import br.prospero.fo.cotacao.dominio.SessaoCotacao;
import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.dominio.LadoOrdem;
import br.prospero.fo.ordens.dominio.MatchingIncoerenteException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL real em schema próprio; requer MATCHING_TEST_DB_* de banco descartável. */
@QuarkusTest
@TestProfile(CotacaoPostgresIT.Banco.class)
class CotacaoPostgresIT {
    private static final String SCHEMA = schemaDaExecucao();

    private static String schemaDaExecucao() {
        synchronized (System.getProperties()) {
            return System.getProperties().computeIfAbsent("prospero.it.fo.cotacao.schema",
                    chave -> "fo_cotacao_it_" + UUID.randomUUID().toString().replace("-", "")).toString();
        }
    }

    public static class Banco implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            try (var c = conexao(); var s = c.createStatement()) {
                s.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
            } catch (Exception erro) {
                throw new IllegalStateException("Banco descartável obrigatório", erro);
            }
            return Map.of("quarkus.datasource.active", "true", "quarkus.hibernate-orm.active", "true",
                    "quarkus.datasource.jdbc.url", System.getenv("MATCHING_TEST_DB_URL") + "?currentSchema=" + SCHEMA,
                    "quarkus.datasource.username", System.getenv("MATCHING_TEST_DB_USER"),
                    "quarkus.datasource.password", System.getenv("MATCHING_TEST_DB_PASSWORD"),
                    "quarkus.flyway.migrate-at-start", "true", "quarkus.flyway.schemas", SCHEMA,
                    "prospero.cotacao.repositorio-memoria", "false");
        }
    }

    private static java.sql.Connection conexao() throws Exception {
        return DriverManager.getConnection(System.getenv("MATCHING_TEST_DB_URL"),
                System.getenv("MATCHING_TEST_DB_USER"), System.getenv("MATCHING_TEST_DB_PASSWORD"));
    }

    @AfterAll
    static void removerSchemaProprio() throws Exception {
        try (var c = conexao(); var s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Inject br.prospero.fo.cotacao.jpa.RepositorioCotacaoJpa persistencia;

    @Test
    void existeAdaptadorPersistenteParaCotacaoReal() {
        assertNotNull(persistencia, "Cotação precisa de adaptador persistente PostgreSQL");
    }

    private static final java.util.concurrent.atomic.AtomicLong NUMERO = new java.util.concurrent.atomic.AtomicLong();
    private static final LocalDate DIA = LocalDate.of(2026, 10, 22);

    private RepositorioCotacao repositorio() { return persistencia; }

    private static EventoMatching.Alvo alvo() {
        UUID id = UUID.randomUUID();
        return new EventoMatching.Alvo(id, id, "CONTA-002");
    }

    private static UUID admissao(EventoMatching.Alvo alvo) {
        return UUID.nameUUIDFromBytes(("admissao:" + alvo.intencao()).getBytes(StandardCharsets.UTF_8));
    }

    private static EventoMatching oferta(EventoMatching.Alvo alvo, String ticker, LocalDate dia,
                                          LadoOrdem lado, String preco, long quantidade) {
        return evento(ticker, dia, admissao(alvo), new EventoMatching.Oferta(alvo, lado, new BigDecimal(preco), quantidade));
    }

    private static EventoMatching evento(String ticker, LocalDate dia, UUID correlacao, EventoMatching.Detalhe detalhe) {
        UUID id = UUID.randomUUID();
        return new EventoMatching(id, correlacao, ticker, dia,
                br.prospero.fo.ordens.helper.EnvelopeMatching.assinatura(id.toString().getBytes(StandardCharsets.UTF_8)), detalhe);
    }

    private static EventoMatching negocio(EventoMatching.Alvo compra, EventoMatching.Alvo venda,
                                           String ticker, LocalDate dia, String preco, long quantidade) {
        return evento(ticker, dia, admissao(compra), new EventoMatching.Negocio(
                ticker + "-" + dia + "-" + NUMERO.incrementAndGet(), new BigDecimal(preco), quantidade,
                compra, venda, LadoOrdem.COMPRA));
    }

    private static void decimal(String esperado, BigDecimal atual) {
        assertNotNull(atual);
        assertEquals(0, new BigDecimal(esperado).compareTo(atual));
    }

    @Test
    void livroAtualizaSemNegocioEExecucaoParcialSomaUmaVez() {
        var compra = alvo(); var venda = alvo();
        LocalDate dia = DIA.minusDays(20);
        assertTrue(repositorio().aplicar(oferta(venda, "ALFA3", dia, LadoOrdem.VENDA, "10.05", 10)));
        var n = negocio(compra, venda, "ALFA3", dia, "10.05", 3);
        assertTrue(repositorio().aplicar(n));
        var antes = repositorio().sessao("ALFA3", dia).orElseThrow();
        assertFalse(repositorio().aplicar(n));
        var depois = repositorio().sessao("ALFA3", dia).orElseThrow();
        assertEquals(antes, depois, "Reentrega não altera revisão nem instante");
        assertEquals(3, depois.volumeQuantidade());
        decimal("30.15", depois.volumeFinanceiro());
        decimal("10.05", depois.ultimoPreco());
        decimal("10.0500", depois.vwap());
    }

    @Test
    void identidadeDivergenteFalhaSemAlterarAcumuladores() {
        var compra = alvo(); var venda = alvo(); LocalDate dia = DIA.minusDays(21);
        repositorio().aplicar(oferta(venda, "ALFA3", dia, LadoOrdem.VENDA, "10.00", 10));
        var n = negocio(compra, venda, "ALFA3", dia, "10.00", 2);
        repositorio().aplicar(n);
        var antes = repositorio().sessao("ALFA3", dia).orElseThrow();
        var conflitante = new EventoMatching(n.id(), n.correlacao(), n.ticker(), n.sessao(),
                "b".repeat(64), n.detalhe(), new EventoMatching.Contexto(n.causacao(), n.versao(), null, null));
        assertThrows(MatchingIncoerenteException.class, () -> repositorio().aplicar(conflitante));
        assertEquals(antes, repositorio().sessao("ALFA3", dia).orElseThrow());
    }

    @Test
    void negocioReenvelopadoNaoDuplicaVolume() {
        var compra = alvo(); var venda = alvo(); LocalDate dia = DIA.minusDays(22);
        repositorio().aplicar(oferta(venda, "ALFA3", dia, LadoOrdem.VENDA, "10.00", 10));
        var n = negocio(compra, venda, "ALFA3", dia, "10.00", 2);
        assertTrue(repositorio().aplicar(n));
        var reenvelopado = evento("ALFA3", dia, n.correlacao(), n.detalhe());
        assertFalse(repositorio().aplicar(reenvelopado));
        assertEquals(2, repositorio().sessao("ALFA3", dia).orElseThrow().volumeQuantidade());
    }

    @Test
    void negocioSemPassivaReverteDeduplicacaoEPermiteReplayCorrigido() {
        var compra = alvo(); var venda = alvo(); LocalDate dia = DIA.minusDays(23);
        var n = negocio(compra, venda, "ALFA3", dia, "10.00", 2);
        assertThrows(MatchingIncoerenteException.class, () -> repositorio().aplicar(n));
        assertTrue(repositorio().sessao("ALFA3", dia).isEmpty());
        repositorio().aplicar(oferta(venda, "ALFA3", dia, LadoOrdem.VENDA, "10.00", 10));
        assertTrue(repositorio().aplicar(n));
        assertEquals(2, repositorio().sessao("ALFA3", dia).orElseThrow().volumeQuantidade());
    }

    @Test
    void quantidadeMaiorQuePassivaNaoConsomeLivroNemIdentidade() {
        var compra = alvo(); var venda = alvo(); LocalDate dia = DIA.minusDays(24);
        repositorio().aplicar(oferta(venda, "ALFA3", dia, LadoOrdem.VENDA, "10.00", 1));
        var n = negocio(compra, venda, "ALFA3", dia, "10.00", 2);
        var antes = repositorio().sessao("ALFA3", dia).orElseThrow();
        assertThrows(MatchingIncoerenteException.class, () -> repositorio().aplicar(n));
        assertEquals(antes, repositorio().sessao("ALFA3", dia).orElseThrow());
    }

    @Test
    void livroAgregaOrdenaLimitaCincoNiveisEPreservaTotais() {
        LocalDate dia = DIA.plusDays(50);
        for (int i = 0; i < 7; i++) {
            repositorio().aplicar(oferta(alvo(), "BETA3", dia, LadoOrdem.COMPRA,
                    new BigDecimal("20.00").subtract(new BigDecimal(i).movePointLeft(2)).toPlainString(), 2));
        }
        repositorio().aplicar(oferta(alvo(), "BETA3", dia, LadoOrdem.COMPRA, "20.00", 3));
        var livro = repositorio().livro("BETA3").orElseThrow();
        assertEquals(dia, livro.sessao().sessao());
        assertEquals(5, livro.compras().niveis().size());
        assertEquals(7, livro.compras().niveisTotal());
        assertEquals(17, livro.compras().quantidadeTotal());
        var primeiro = livro.compras().niveis().getFirst();
        decimal("20.00", primeiro.preco());
        assertEquals(5, primeiro.quantidade());
        assertEquals(2, primeiro.ofertas());
        assertEquals(5, primeiro.quantidadeAcumulada());
        assertEquals(13, livro.compras().niveis().getLast().quantidadeAcumulada());
        assertNull(livro.sessao().ultimoPreco()); assertNull(livro.sessao().vwap());
    }

    @Test
    void fechamentoEsvaziaLivroMantemIndicadoresEDistingueSessaoNova() {
        LocalDate dia = DIA.plusDays(60); var compra = alvo(); var venda = alvo();
        repositorio().aplicar(oferta(venda, "GAMA3", dia, LadoOrdem.VENDA, "30.00", 8));
        repositorio().aplicar(negocio(compra, venda, "GAMA3", dia, "30.00", 3));
        UUID raiz = EventoMatching.encerramento("GAMA3", dia);
        long expiradas = 1;
        UUID id = UUID.nameUUIDFromBytes(("matching:" + raiz + ":" + expiradas).getBytes(StandardCharsets.UTF_8));
        var encerramento = new EventoMatching(id, raiz, "GAMA3", dia, "c".repeat(64),
                new EventoMatching.SessaoEncerrada(expiradas), new EventoMatching.Contexto(raiz, 4, null, null));
        assertTrue(repositorio().aplicar(encerramento));
        assertFalse(repositorio().aplicar(encerramento));
        var livro = repositorio().livro("GAMA3").orElseThrow();
        assertTrue(livro.sessao().encerrada());
        assertEquals(0, livro.vendas().quantidadeTotal());
        assertEquals(3, livro.sessao().volumeQuantidade());
        decimal("30.0000", livro.sessao().vwap());
        repositorio().aplicar(oferta(alvo(), "GAMA3", dia.plusDays(1), LadoOrdem.COMPRA, "29.00", 1));
        var nova = repositorio().livro("GAMA3").orElseThrow();
        assertEquals(dia.plusDays(1), nova.sessao().sessao());
        assertEquals(0, nova.sessao().volumeQuantidade()); assertNull(nova.sessao().vwap());
    }

    @Test
    void reducaoRetiradaEOfertaRepetidaRespeitamRevisao() {
        LocalDate dia = DIA.plusDays(70); var venda = alvo();
        var oferta = oferta(venda, "DELTA3", dia, LadoOrdem.VENDA, "40.00", 10);
        assertTrue(repositorio().aplicar(oferta));
        long revisao = repositorio().sessao("DELTA3", dia).orElseThrow().revisao();
        assertFalse(repositorio().aplicar(evento("DELTA3", dia, admissao(venda), oferta.detalhe())));
        assertEquals(revisao, repositorio().sessao("DELTA3", dia).orElseThrow().revisao());
        assertTrue(repositorio().aplicar(evento("DELTA3", dia, admissao(venda), new EventoMatching.Reduzida(venda, 4, 6))));
        assertEquals(6, repositorio().livro("DELTA3").orElseThrow().vendas().quantidadeTotal());
        assertTrue(repositorio().aplicar(evento("DELTA3", dia, admissao(venda),
                new EventoMatching.Encerrada(venda, "SALDO_CANCELADO_IOC", 0, 6))));
        assertEquals(0, repositorio().livro("DELTA3").orElseThrow().vendas().quantidadeTotal());
        assertEquals(0, repositorio().sessao("DELTA3", dia).orElseThrow().volumeQuantidade());
    }

    @Test
    void precoLoteECorrelacaoInvalidosNaoCriamSessao() {
        LocalDate dia = DIA.minusDays(80); var alvo = alvo();
        var foraTick = oferta(alvo, "OMEGA3", dia, LadoOrdem.VENDA, "50.01", 100);
        var foraLote = oferta(alvo, "OMEGA3", dia, LadoOrdem.VENDA, "50.00", 1);
        var valido = oferta(alvo, "OMEGA3", dia, LadoOrdem.VENDA, "50.00", 100);
        var foraCorrelacao = new EventoMatching(valido.id(), UUID.randomUUID(), valido.ticker(), valido.sessao(),
                valido.assinatura(), valido.detalhe());
        for (var invalido : java.util.List.of(foraTick, foraLote, foraCorrelacao)) {
            assertThrows(MatchingIncoerenteException.class, () -> repositorio().aplicar(invalido));
        }
        assertTrue(repositorio().sessao("OMEGA3", dia).isEmpty());
        assertTrue(repositorio().aplicar(valido), "Recusa não consome id do evento");
    }

    @Test
    void migracaoV10PreservaDedupHistoricaSemGerarCotacao() throws Exception {
        String proprio = "fo_cotacao_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var configuracao = org.flywaydb.core.Flyway.configure().dataSource(System.getenv("MATCHING_TEST_DB_URL"),
                System.getenv("MATCHING_TEST_DB_USER"), System.getenv("MATCHING_TEST_DB_PASSWORD"))
                .schemas(proprio).defaultSchema(proprio).locations("classpath:db/migration");
        UUID id = UUID.randomUUID();
        try {
            configuracao.target("9").load().migrate();
            try (var c = conexao(); var s = c.createStatement()) {
                c.setSchema(proprio);
                s.execute("INSERT INTO matching_evento(id,assinatura) VALUES('" + id + "','" + "a".repeat(64) + "')");
            }
            configuracao.target("latest").load().migrate();
            try (var c = conexao(); var s = c.createStatement()) {
                c.setSchema(proprio);
                try (var r = s.executeQuery("SELECT count(*) FROM matching_evento WHERE id='" + id + "'")) {
                    assertTrue(r.next()); assertEquals(1, r.getInt(1));
                }
                for (String tabela : java.util.List.of("cotacao_evento", "cotacao_negocio", "cotacao_sessao", "cotacao_oferta")) {
                    try (var r = s.executeQuery("SELECT count(*) FROM " + tabela)) {
                        assertTrue(r.next()); assertEquals(0, r.getInt(1));
                    }
                }
            }
        } finally {
            try (var c = conexao(); var s = c.createStatement()) { s.execute("DROP SCHEMA IF EXISTS " + proprio + " CASCADE"); }
        }
    }

    @Test
    void duplicataConcorrenteTemUmUnicoEfeito() throws Exception {
        var venda = alvo(); LocalDate dia = DIA.minusDays(81);
        var oferta = oferta(venda, "ALFA3", dia, LadoOrdem.VENDA, "10.00", 10);
        var inicio = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> aplicar = () -> {
                assertTrue(inicio.await(5, java.util.concurrent.TimeUnit.SECONDS));
                return repositorio().aplicar(oferta);
            };
            var a = executor.submit(aplicar); var b = executor.submit(aplicar); inicio.countDown();
            boolean primeiro = a.get(10, java.util.concurrent.TimeUnit.SECONDS);
            boolean segundo = b.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(primeiro, segundo, "Um aplica, outro reconhece reentrega");
        }
        assertEquals(1, repositorio().sessao("ALFA3", dia).orElseThrow().revisao());
    }

    @Test
    void mesmoNegocioComConteudoDivergenteNaoConsomePassiva() {
        var compra = alvo(); var venda = alvo(); LocalDate dia = DIA.minusDays(82);
        repositorio().aplicar(oferta(venda, "ALFA3", dia, LadoOrdem.VENDA, "10.00", 10));
        var n = negocio(compra, venda, "ALFA3", dia, "10.00", 2);
        repositorio().aplicar(n);
        var original = (EventoMatching.Negocio) n.detalhe();
        var divergente = new EventoMatching.Negocio(original.id(), original.preco(), 3, compra, venda, LadoOrdem.COMPRA);
        var antes = repositorio().sessao("ALFA3", dia).orElseThrow();
        assertThrows(MatchingIncoerenteException.class,
                () -> repositorio().aplicar(evento("ALFA3", dia, admissao(compra), divergente)));
        assertEquals(antes, repositorio().sessao("ALFA3", dia).orElseThrow());
    }

    @Test
    void encerramentoIncoerenteNaoRetiraOfertaNemConsomeIdentidade() {
        var venda = alvo(); LocalDate dia = DIA.minusDays(83);
        repositorio().aplicar(oferta(venda, "ALFA3", dia, LadoOrdem.VENDA, "10.00", 10));
        var antes = repositorio().sessao("ALFA3", dia).orElseThrow();
        var invalido = evento("ALFA3", dia, admissao(venda), new EventoMatching.Encerrada(venda, "SALDO_CANCELADO_IOC", 0, 9));
        assertThrows(MatchingIncoerenteException.class, () -> repositorio().aplicar(invalido));
        assertEquals(antes, repositorio().sessao("ALFA3", dia).orElseThrow());
        assertThrows(MatchingIncoerenteException.class, () -> repositorio().aplicar(
                evento("ALFA3", dia, admissao(venda), new EventoMatching.Encerrada(venda, "EXECUTADA", 10, 0))));
        var corrigido = new EventoMatching(invalido.id(), invalido.correlacao(), invalido.ticker(), invalido.sessao(),
                "d".repeat(64), new EventoMatching.Encerrada(venda, "SALDO_CANCELADO_IOC", 0, 10));
        assertTrue(repositorio().aplicar(corrigido), "Falha não consome deduplicação");
    }
}
