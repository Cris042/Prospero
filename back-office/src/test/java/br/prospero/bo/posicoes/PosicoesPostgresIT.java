package br.prospero.bo.posicoes;

import br.prospero.bo.posicoes.actor.AplicarPosicoesActor;
import br.prospero.bo.posicoes.dominio.*;
import br.prospero.bo.posicoes.helper.EnvelopeMatching;
import br.prospero.bo.posicoes.jpa.CarteiraPosicoesJpa;
import br.prospero.bo.posicoes.jpa.CheckpointPosicoesJpa;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Cada execução usa schema aleatório num banco efêmero fornecido pelo harness QA/CI. */
@QuarkusTest
@TestProfile(PosicoesPostgresIT.Banco.class)
class PosicoesPostgresIT {
    private static final String SCHEMA = schemaDaExecucao();
    private static final String TOPICO = "ABCDEFGHIJKLMNOPQRSTUV";
    private static final LocalDate DIA = LocalDate.of(2026, 10, 23);
    @Inject AplicarPosicoesActor aplicar;
    @Inject CheckpointPosicoesJpa checkpoint;
    @Inject CarteiraPosicoesJpa carteiras;

    private static String schemaDaExecucao() {
        synchronized (System.getProperties()) {
            return System.getProperties().computeIfAbsent("prospero.it.bo.posicoes.schema",
                    chave -> "bo_posicoes_it_" + UUID.randomUUID().toString().replace("-", "")).toString();
        }
    }

    public static class Banco implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            try (var c = base(); var s = c.createStatement()) { s.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA); }
            catch (Exception erro) { throw new IllegalStateException("Banco descartável próprio obrigatório", erro); }
            return Map.of("quarkus.datasource.active", "true", "quarkus.hibernate-orm.active", "true",
                    "quarkus.datasource.jdbc.url", System.getenv("MATCHING_TEST_DB_URL") + "?currentSchema=" + SCHEMA,
                    "quarkus.datasource.username", System.getenv("MATCHING_TEST_DB_USER"),
                    "quarkus.datasource.password", System.getenv("MATCHING_TEST_DB_PASSWORD"),
                    "quarkus.flyway.migrate-at-start", "true", "quarkus.flyway.schemas", SCHEMA);
        }
    }

    @BeforeEach
    void prepararSomenteSchemaDaExecucao() throws Exception {
        try (var c = conexao(); var s = c.createStatement()) {
            // V3: fatos, liquidações e razão referenciam bo_negocio (TRUNCATE não aciona os gatilhos append-only).
            s.execute("TRUNCATE bo_negocio_origem,bo_lancamento,bo_liquidacao,bo_negocio_fato,bo_transporte,bo_evento,bo_negocio,bo_checkpoint,bo_log");
            s.execute("UPDATE bo_posicao SET quantidade=0,medio_n=null,medio_d=null,realizado_n='0',realizado_d='1'");
            s.execute("UPDATE bo_cotacao SET preco=null,sessao=null,encerrada=false,revisao=0,atualizada_em=null");
        }
        checkpoint.iniciar(TOPICO);
    }

    @AfterAll
    static void removerSomenteSchemaDaExecucao() throws Exception {
        try (var c = base(); var s = c.createStatement()) { s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE"); }
    }

    @Test
    void bootstrapTresParticoesIdempotenteMasNaoAceitaOutroLogOuEstadoParcial() throws Exception {
        checkpoint.iniciar(TOPICO);
        assertEquals(3, numero("SELECT count(*) FROM bo_checkpoint"));
        assertEquals(20, numero("SELECT count(*) FROM bo_posicao"));
        assertThrows(ProjecaoIncoerenteException.class, () -> checkpoint.iniciar("ZZZZZZZZZZZZZZZZZZZZZZ"));
        try (var c = conexao(); var s = c.createStatement()) { s.execute("DELETE FROM bo_checkpoint WHERE particao=2"); }
        assertThrows(ProjecaoIncoerenteException.class, () -> checkpoint.iniciar(TOPICO));
    }

    @Test
    void coordenadaAntigaERepublicacaoDoMesmoFatoNaoRepetemPosicaoOuCotacao() throws Exception {
        EventoMatching negocio = negocio("1", "100.00", 10, "CONTA-002", "CONTA-003");
        aplicar.aplicar(coordenada(0, negocio), negocio);
        aplicar.aplicar(coordenada(0, negocio), negocio);
        aplicar.aplicar(coordenada(1, negocio), negocio);
        EventoMatching envelopeNovo = new EventoMatching(UUID.randomUUID(), negocio.correlacao(), negocio.ticker(),
                negocio.sessao(), assinatura("outro-envelope"), negocio.detalhe());
        aplicar.aplicar(coordenada(2, envelopeNovo), envelopeNovo);
        assertEquals(10, posicao("CONTA-002").quantidade());
        assertEquals(-10, posicao("CONTA-003").quantidade());
        assertEquals("100.0000", posicao("CONTA-002").medio().formatar());
        assertEquals(3, checkpoint.proximo(0));
        assertEquals(3, numero("SELECT count(*) FROM bo_transporte"));
        assertEquals(2, numero("SELECT count(*) FROM bo_evento"));
        assertEquals(1, numero("SELECT count(*) FROM bo_negocio"));
        assertEquals(1, numero("SELECT revisao FROM bo_cotacao WHERE ticker='ALFA3'"));
    }

    @Test
    void conflitoDeNegocioOuCoordenadaELacunaBloqueiamSemAvancar() throws Exception {
        EventoMatching original = negocio("1", "100.00", 10, "CONTA-002", "CONTA-003");
        aplicar.aplicar(coordenada(0, original), original);
        EventoMatching.Negocio n = (EventoMatching.Negocio) original.detalhe();
        EventoMatching divergente = new EventoMatching(UUID.randomUUID(), original.correlacao(), "ALFA3", DIA,
                assinatura("conteudo-divergente"), new EventoMatching.Negocio(n.id(), new BigDecimal("101.00"), 10,
                n.compra(), n.venda(), n.agressor()));
        assertThrows(ProjecaoIncoerenteException.class, () -> aplicar.aplicar(coordenada(1, divergente), divergente));
        assertThrows(ProjecaoIncoerenteException.class, () -> aplicar.aplicar(coordenada(0, divergente), divergente));
        EventoMatching seguinte = negocio("2", "110.00", 1, "CONTA-002", "CONTA-003");
        assertThrows(ProjecaoIncoerenteException.class, () -> aplicar.aplicar(coordenada(2, seguinte), seguinte));
        assertEquals(1, checkpoint.proximo(0));
        assertEquals(1, numero("SELECT count(*) FROM bo_evento"));
        assertEquals(1, numero("SELECT count(*) FROM bo_negocio"));
        assertEquals(10, posicao("CONTA-002").quantidade());
    }

    @Test
    void falhaNoSegundoParticipanteRevertePrimeiroFatoCotacaoECheckpoint() throws Exception {
        try (var c = conexao(); var s = c.prepareStatement(
                "UPDATE bo_posicao SET quantidade=?,medio_n='1',medio_d='1' WHERE conta='CONTA-003' AND ticker='ALFA3'")) {
            s.setLong(1, -Long.MAX_VALUE);
            s.executeUpdate();
        }
        EventoMatching negocio = negocio("1", "1.00", 1, "CONTA-002", "CONTA-003");
        assertThrows(ArithmeticException.class, () -> aplicar.aplicar(coordenada(0, negocio), negocio));
        assertEquals(0, posicao("CONTA-002").quantidade());
        assertEquals(-Long.MAX_VALUE, posicao("CONTA-003").quantidade());
        conferirRollbackIntegral();
    }

    @Test
    void orcamentoRacionalExcedidoPreservaEstadoEProgressoPersistidos() throws Exception {
        BigInteger base = BigInteger.ONE.shiftLeft(Racional.LIMITE_BITS - 1);
        Racional medio = Racional.fracao(base.add(BigInteger.ONE), base.subtract(BigInteger.ONE));
        try (var c = conexao(); var s = c.prepareStatement(
                "UPDATE bo_posicao SET quantidade=1000003,medio_n=?,medio_d=? WHERE conta='CONTA-002' AND ticker='ALFA3'")) {
            s.setString(1, medio.numerador().toString()); s.setString(2, medio.denominador().toString()); s.executeUpdate();
        }
        EventoMatching negocio = negocio("1", "1.00", 1, "CONTA-002", "CONTA-003");
        assertThrows(IllegalStateException.class, () -> aplicar.aplicar(coordenada(0, negocio), negocio));
        assertEquals(1000003, posicao("CONTA-002").quantidade());
        assertEquals(medio, posicao("CONTA-002").medio());
        assertEquals(0, posicao("CONTA-003").quantidade());
        conferirRollbackIntegral();
    }

    @Test
    void autonegociacaoPreservaMedioERealizadoMasAtualizaCotacao() throws Exception {
        EventoMatching abertura = negocio("1", "100.00", 10, "CONTA-002", "CONTA-003");
        aplicar.aplicar(coordenada(0, abertura), abertura);
        Posicao anterior = posicao("CONTA-002");
        EventoMatching self = negocio("2", "110.00", 4, "CONTA-002", "CONTA-002");
        aplicar.aplicar(coordenada(1, self), self);
        assertEquals(anterior, posicao("CONTA-002"));
        assertEquals(-10, posicao("CONTA-003").quantidade());
        EstadoPosicao estado = carteiras.consultar("CONTA-002").getFirst();
        assertEquals(new BigDecimal("110.00"), estado.cotacao());
        assertEquals(2, estado.revisao());
        assertEquals("100.0000", estado.posicao().naoRealizado(estado.cotacao()).formatar());
    }

    @Test
    void duasGeracoesConcorrentesConfirmamUmUnicoEfeito() throws Exception {
        EventoMatching negocio = negocio("1", "100.00", 10, "CONTA-002", "CONTA-003");
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Void>> tarefas = new ArrayList<>();
            for (int i = 0; i < 8; i++) tarefas.add(() -> { aplicar.aplicar(coordenada(0, negocio), negocio); return null; });
            for (var futuro : pool.invokeAll(tarefas)) futuro.get(20, TimeUnit.SECONDS);
        }
        assertEquals(10, posicao("CONTA-002").quantidade());
        assertEquals(-10, posicao("CONTA-003").quantidade());
        assertEquals(1, checkpoint.proximo(0));
        assertEquals(1, numero("SELECT count(*) FROM bo_negocio"));
        assertEquals(1, numero("SELECT revisao FROM bo_cotacao WHERE ticker='ALFA3'"));
    }

    @Test
    void reinicioRetomaCheckpointEEncerramentoImpedeNegocioPosterior() throws Exception {
        EventoMatching negocio = negocio("1", "100.00", 10, "CONTA-002", "CONTA-003");
        aplicar.aplicar(coordenada(0, negocio), negocio);
        checkpoint.iniciar(TOPICO);
        aplicar.aplicar(coordenada(0, negocio), negocio);
        UUID causa = EventoMatching.encerramento("ALFA3", DIA);
        UUID id = UUID.nameUUIDFromBytes(("matching:" + causa + ":0").getBytes(StandardCharsets.UTF_8));
        EventoMatching fim = new EventoMatching(id, causa, "ALFA3", DIA, assinatura("marco"),
                new EventoMatching.SessaoEncerrada(0), causa, 4);
        aplicar.aplicar(coordenada(1, fim), fim);
        assertTrue(carteiras.consultar("CONTA-002").getFirst().encerrada());
        EventoMatching tardio = negocio("2", "101.00", 1, "CONTA-002", "CONTA-003");
        assertThrows(ProjecaoIncoerenteException.class, () -> aplicar.aplicar(coordenada(2, tardio), tardio));
        assertEquals(2, checkpoint.proximo(0));
        assertEquals(10, posicao("CONTA-002").quantidade());
    }

    @Test
    void expansaoV1ParaV2MantemHistoricoFlywayECriaVintePosicoesZeradas() throws Exception {
        String upgrade = "bo_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            org.flywaydb.core.Flyway.configure().dataSource(System.getenv("MATCHING_TEST_DB_URL"),
                    System.getenv("MATCHING_TEST_DB_USER"), System.getenv("MATCHING_TEST_DB_PASSWORD"))
                    .schemas(upgrade).target("1").load().migrate();
            org.flywaydb.core.Flyway.configure().dataSource(System.getenv("MATCHING_TEST_DB_URL"),
                    System.getenv("MATCHING_TEST_DB_USER"), System.getenv("MATCHING_TEST_DB_PASSWORD"))
                    .schemas(upgrade).target("2").load().migrate();
            try (var c = base(); var s = c.createStatement(); var r = s.executeQuery(
                    "SELECT count(*) FROM " + upgrade + ".bo_posicao WHERE quantidade=0 AND medio_n IS NULL")) {
                assertTrue(r.next()); assertEquals(20, r.getLong(1));
            }
        } finally {
            try (var c = base(); var s = c.createStatement()) { s.execute("DROP SCHEMA IF EXISTS " + upgrade + " CASCADE"); }
        }
    }

    private void conferirRollbackIntegral() throws Exception {
        assertEquals(0, checkpoint.proximo(0));
        assertEquals(0, numero("SELECT count(*) FROM bo_evento"));
        assertEquals(0, numero("SELECT count(*) FROM bo_negocio"));
        assertEquals(0, numero("SELECT count(*) FROM bo_transporte"));
        assertEquals(0, numero("SELECT revisao FROM bo_cotacao WHERE ticker='ALFA3'"));
    }
    private Posicao posicao(String conta) { return carteiras.consultar(conta).getFirst().posicao(); }
    private static Coordenada coordenada(long offset, EventoMatching e) { return new Coordenada(TOPICO, 0, offset, e.assinatura()); }
    private static EventoMatching negocio(String numero, String preco, long quantidade, String compra, String venda) {
        var c = new EventoMatching.Alvo(UUID.randomUUID(), UUID.randomUUID(), compra);
        var v = new EventoMatching.Alvo(UUID.randomUUID(), UUID.randomUUID(), venda);
        UUID id = UUID.randomUUID();
        return new EventoMatching(id, UUID.nameUUIDFromBytes(("admissao:" + c.intencao()).getBytes(StandardCharsets.UTF_8)),
                "ALFA3", DIA, assinatura(id.toString()), new EventoMatching.Negocio("ALFA3-" + DIA + "-" + numero,
                new BigDecimal(preco), quantidade, c, v, LadoOrdem.COMPRA));
    }
    private static String assinatura(String valor) { return EnvelopeMatching.assinatura(valor.getBytes(StandardCharsets.UTF_8)); }
    private static long numero(String sql) throws Exception {
        try (var c = conexao(); var s = c.createStatement(); var r = s.executeQuery(sql)) { assertTrue(r.next()); return r.getLong(1); }
    }
    private static Connection base() throws Exception {
        return DriverManager.getConnection(System.getenv("MATCHING_TEST_DB_URL"),
                System.getenv("MATCHING_TEST_DB_USER"), System.getenv("MATCHING_TEST_DB_PASSWORD"));
    }
    private static Connection conexao() throws Exception { Connection c = base(); c.setSchema(SCHEMA); return c; }
}
