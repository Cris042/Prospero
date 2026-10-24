package br.prospero.bo.razao;

import br.prospero.bo.posicoes.actor.AplicarPosicoesActor;
import br.prospero.bo.posicoes.dominio.*;
import br.prospero.bo.posicoes.helper.EnvelopeMatching;
import br.prospero.bo.posicoes.jpa.CarteiraPosicoesJpa;
import br.prospero.bo.posicoes.jpa.CheckpointPosicoesJpa;
import br.prospero.bo.razao.dominio.*;
import br.prospero.bo.razao.jpa.RazaoJpa;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** RF08b em PostgreSQL real: schema aleatório num banco efêmero fornecido pelo harness QA/CI. */
@QuarkusTest
@TestProfile(RazaoPostgresIT.Banco.class)
class RazaoPostgresIT {
    private static final String SCHEMA = schemaDaExecucao();
    private static final String TOPICO = "ABCDEFGHIJKLMNOPQRSTUV";
    private static final LocalDate DIA = LocalDate.of(2026, 10, 23);
    @Inject AplicarPosicoesActor aplicar;
    @Inject CheckpointPosicoesJpa checkpoint;
    @Inject CarteiraPosicoesJpa carteiras;
    @Inject RazaoJpa razao;

    private static String schemaDaExecucao() {
        synchronized (System.getProperties()) {
            return System.getProperties().computeIfAbsent("prospero.it.bo.razao.schema",
                    chave -> "bo_razao_it_" + UUID.randomUUID().toString().replace("-", "")).toString();
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
            // TRUNCATE não dispara os gatilhos de linha append-only; só o harness descartável limpa o schema.
            s.execute("TRUNCATE bo_lancamento,bo_liquidacao,bo_negocio_fato,bo_transporte,bo_evento,bo_negocio,bo_checkpoint,bo_log");
            s.execute("UPDATE bo_posicao SET quantidade=0,medio_n=null,medio_d=null,realizado_n='0',realizado_d='1'");
            s.execute("UPDATE bo_cotacao SET preco=null,sessao=null,encerrada=false,revisao=0,atualizada_em=null");
            s.execute("DROP TRIGGER IF EXISTS it_falha ON bo_liquidacao");
            s.execute("DROP TRIGGER IF EXISTS it_desbalanceia ON bo_lancamento");
        }
        checkpoint.iniciar(TOPICO);
    }

    @AfterAll
    static void removerSomenteSchemaDaExecucao() throws Exception {
        try (var c = base(); var s = c.createStatement()) { s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE"); }
    }

    @Test
    void negocioGravaFatoLancamentosD0PosicoesELiquidacoesNaMesmaTransacao() throws Exception {
        EventoMatching negocio = negocio("ALFA3", DIA, "1", "20.00", 100, "CONTA-001", "CONTA-002");
        aplicar.aplicar(coordenada(0, negocio), negocio);
        assertEquals(List.of("ALFA3|2026-10-23|2026-10-27|20.00|100|2000.00|CONTA-001|CONTA-002"), linhas("""
                SELECT ticker||'|'||data_sessao||'|'||data_liquidacao||'|'||preco||'|'||quantidade||'|'||valor||'|'
                       ||conta_compra||'|'||conta_venda FROM bo_negocio_fato"""));
        assertEquals(List.of(
                "CONTA-001|COMPRADOR|D0|CAMARA|D|BRL|2000.00", "CONTA-001|COMPRADOR|D0|CAIXA|C|BRL|2000.00",
                "CONTA-001|COMPRADOR|D0|CUSTODIA_A_RECEBER|D|ALFA3|100", "CONTA-001|COMPRADOR|D0|CAMARA|C|ALFA3|100",
                "CONTA-002|VENDEDOR|D0|CAMARA|D|ALFA3|100", "CONTA-002|VENDEDOR|D0|CUSTODIA|C|ALFA3|100",
                "CONTA-002|VENDEDOR|D0|CAIXA_A_RECEBER|D|BRL|2000.00", "CONTA-002|VENDEDOR|D0|CAMARA|C|BRL|2000.00"),
                linhas("""
                SELECT conta||'|'||lado||'|'||momento||'|'||conta_contabil||'|'||natureza||'|'||unidade||'|'
                       ||coalesce(valor::text,quantidade::text) FROM bo_lancamento ORDER BY id"""));
        assertEquals(0, numero(DESBALANCEADAS), "Σdébitos = Σcréditos por transação e unidade");
        assertEquals(2, numero("SELECT count(DISTINCT transacao) FROM bo_lancamento"));
        assertEquals(List.of("COMPRADOR|CONTA-001|PENDENTE", "VENDEDOR|CONTA-002|PENDENTE"),
                linhas("SELECT lado||'|'||conta||'|'||estado FROM bo_liquidacao ORDER BY id"));
        assertEquals(100, posicao("CONTA-001").quantidade());
        assertEquals(-100, posicao("CONTA-002").quantidade());
        assertEquals(1, checkpoint.proximo(0));
        assertEquals(0, numero("SELECT count(*) FROM bo_lancamento WHERE valor IS NOT NULL AND scale(valor)<>2"));
    }

    @Test
    void autonegociacaoGravaOsDoisLadosComEfeitoNeutroNaPosicao() throws Exception {
        EventoMatching self = negocio("DELTA3", DIA, "1", "40.00", 3, "CONTA-002", "CONTA-002");
        aplicar.aplicar(coordenada(0, self), self);
        assertEquals(8, numero("SELECT count(*) FROM bo_lancamento WHERE conta='CONTA-002'"));
        assertEquals(2, numero("SELECT count(*) FROM bo_liquidacao WHERE conta='CONTA-002' AND estado='PENDENTE'"));
        assertEquals(0, numero(DESBALANCEADAS));
        // Câmara fecha em zero por unidade somando os dois lados.
        assertEquals(0, numero("""
                SELECT count(*) FROM (SELECT unidade FROM bo_lancamento WHERE conta_contabil='CAMARA' GROUP BY unidade
                HAVING sum(CASE natureza WHEN 'D' THEN coalesce(valor,quantidade) ELSE -coalesce(valor,quantidade) END)<>0) x"""));
        assertEquals(0, posicao("CONTA-002", "DELTA3").quantidade());
    }

    @Test
    void falhaInjetadaNaRazaoRevertePosicoesCotacaoECheckpoint() throws Exception {
        try (var c = conexao(); var s = c.createStatement()) {
            s.execute("""
                    CREATE OR REPLACE FUNCTION it_falhar() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'falha injetada'; END $$""");
            s.execute("CREATE TRIGGER it_falha BEFORE INSERT ON bo_liquidacao FOR EACH ROW WHEN (NEW.lado='VENDEDOR') "
                    + "EXECUTE FUNCTION it_falhar()");
        }
        EventoMatching negocio = negocio("ALFA3", DIA, "1", "20.00", 100, "CONTA-001", "CONTA-002");
        assertThrows(RuntimeException.class, () -> aplicar.aplicar(coordenada(0, negocio), negocio));
        assertEquals(0, posicao("CONTA-001").quantidade());
        conferirSemEfeito();
    }

    @Test
    void falhaNaPosicaoReverteFatoLancamentosELiquidacoes() throws Exception {
        try (var c = conexao(); var s = c.prepareStatement(
                "UPDATE bo_posicao SET quantidade=?,medio_n='1',medio_d='1' WHERE conta='CONTA-002' AND ticker='ALFA3'")) {
            s.setLong(1, -Long.MAX_VALUE);
            s.executeUpdate();
        }
        EventoMatching negocio = negocio("ALFA3", DIA, "1", "1.00", 1, "CONTA-001", "CONTA-002");
        assertThrows(ArithmeticException.class, () -> aplicar.aplicar(coordenada(0, negocio), negocio));
        conferirSemEfeito();
    }

    @Test
    void invarianteVioladaNoBancoBloqueiaSemEfeitoParaQuarentena() throws Exception {
        try (var c = conexao(); var s = c.createStatement()) {
            s.execute("""
                    CREATE OR REPLACE FUNCTION it_desbalancear() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN NEW.valor := NEW.valor + 0.01; RETURN NEW; END $$""");
            s.execute("CREATE TRIGGER it_desbalanceia BEFORE INSERT ON bo_lancamento FOR EACH ROW "
                    + "WHEN (NEW.conta_contabil='CAIXA') EXECUTE FUNCTION it_desbalancear()");
        }
        EventoMatching negocio = negocio("ALFA3", DIA, "1", "20.00", 100, "CONTA-001", "CONTA-002");
        var erro = assertThrows(ProjecaoIncoerenteException.class, () -> aplicar.aplicar(coordenada(0, negocio), negocio));
        assertEquals("RAZAO_DESBALANCEADA", erro.getMessage());
        assertEquals(0, posicao("CONTA-001").quantidade());
        conferirSemEfeito();
    }

    @Test
    void calendarioInsuficienteBloqueiaAParticaoSemEfeito() throws Exception {
        EventoMatching tardio = negocio("ALFA3", LocalDate.of(2027, 12, 30), "1", "20.00", 1, "CONTA-001", "CONTA-002");
        var erro = assertThrows(ProjecaoIncoerenteException.class, () -> aplicar.aplicar(coordenada(0, tardio), tardio));
        assertEquals("CALENDARIO_INSUFICIENTE", erro.getMessage());
        assertEquals(0, posicao("CONTA-001").quantidade());
        conferirSemEfeito();
    }

    @Test
    void reentregaECoordenadaAntigaNaoDuplicamFatoLancamentosOuLiquidacao() throws Exception {
        EventoMatching negocio = negocio("ALFA3", DIA, "1", "20.00", 100, "CONTA-001", "CONTA-002");
        aplicar.aplicar(coordenada(0, negocio), negocio);
        aplicar.aplicar(coordenada(0, negocio), negocio);
        EventoMatching envelopeNovo = new EventoMatching(UUID.randomUUID(), negocio.correlacao(), negocio.ticker(),
                negocio.sessao(), assinatura("outro-envelope"), negocio.detalhe());
        aplicar.aplicar(coordenada(1, envelopeNovo), envelopeNovo);
        checkpoint.iniciar(TOPICO);
        aplicar.aplicar(coordenada(1, envelopeNovo), envelopeNovo);
        assertEquals(1, numero("SELECT count(*) FROM bo_negocio_fato"));
        assertEquals(8, numero("SELECT count(*) FROM bo_lancamento"));
        assertEquals(2, numero("SELECT count(*) FROM bo_liquidacao"));
        assertEquals(100, posicao("CONTA-001").quantidade());
        assertEquals(2, checkpoint.proximo(0));
    }

    @Test
    void mesmoIdComConteudoDivergenteBloqueiaSemEscreverRazao() throws Exception {
        EventoMatching original = negocio("ALFA3", DIA, "1", "20.00", 100, "CONTA-001", "CONTA-002");
        aplicar.aplicar(coordenada(0, original), original);
        EventoMatching.Negocio n = (EventoMatching.Negocio) original.detalhe();
        EventoMatching divergente = new EventoMatching(UUID.randomUUID(), original.correlacao(), "ALFA3", DIA,
                assinatura("divergente"), new EventoMatching.Negocio(n.id(), new BigDecimal("20.01"), 100,
                n.compra(), n.venda(), n.agressor()));
        var erro = assertThrows(ProjecaoIncoerenteException.class, () -> aplicar.aplicar(coordenada(1, divergente), divergente));
        assertEquals("IDENTIDADE_CONFLITANTE", erro.getMessage());
        assertEquals(8, numero("SELECT count(*) FROM bo_lancamento"));
        assertEquals(List.of("20.00"), linhas("SELECT preco::text FROM bo_negocio_fato"));
        assertEquals(1, checkpoint.proximo(0));
    }

    @Test
    void lancamentoEFatoSaoAppendOnlyELiquidacaoEUnicaPorNegocioELado() throws Exception {
        EventoMatching negocio = negocio("ALFA3", DIA, "1", "20.00", 100, "CONTA-001", "CONTA-002");
        aplicar.aplicar(coordenada(0, negocio), negocio);
        for (String sql : List.of("UPDATE bo_lancamento SET valor=valor+1 WHERE valor IS NOT NULL",
                "DELETE FROM bo_lancamento", "UPDATE bo_negocio_fato SET preco=preco+1", "DELETE FROM bo_negocio_fato",
                "INSERT INTO bo_liquidacao(negocio_id,lado,conta) VALUES('ALFA3-2026-10-23-1','VENDEDOR','CONTA-002')")) {
            try (var c = conexao(); var s = c.createStatement()) {
                assertThrows(SQLException.class, () -> s.executeUpdate(sql), sql);
            }
        }
        assertEquals(8, numero("SELECT count(*) FROM bo_lancamento"));
        assertEquals(List.of("2000.00"), linhas("SELECT valor::text FROM bo_negocio_fato"));
        assertEquals(2, numero("SELECT count(*) FROM bo_liquidacao"));
    }

    @Test
    void negocioLegadoSemFatoEconomicoFalhaFechadoComRazaoSemOrigem() throws Exception {
        try (var c = conexao(); var s = c.createStatement()) {
            s.execute("INSERT INTO bo_negocio(id,assinatura) VALUES('ALFA3-2026-10-22-1','" + "a".repeat(64) + "')");
        }
        var erro = assertThrows(ProjecaoIncoerenteException.class, () -> checkpoint.iniciar(TOPICO));
        assertEquals("RAZAO_SEM_ORIGEM", erro.getMessage());
    }

    @Test
    void expansaoV2ParaV3ComNegociosDaRf07PreservaDadosEDetectaLegadoSemBackfill() throws Exception {
        String upgrade = "bo_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(upgrade, "2");
            try (var c = base(); var s = c.createStatement()) {
                s.execute("SET search_path TO " + upgrade);
                s.execute("INSERT INTO bo_log(id,topico_id) VALUES(1,'" + TOPICO + "')");
                s.execute("INSERT INTO bo_checkpoint(particao,proximo_offset) VALUES(0,1),(1,0),(2,0)");
                s.execute("INSERT INTO bo_negocio(id,assinatura) VALUES('ALFA3-2026-10-22-1','" + "b".repeat(64) + "')");
                s.execute("UPDATE bo_posicao SET quantidade=5,medio_n='20',medio_d='1' WHERE conta='CONTA-001' AND ticker='ALFA3'");
            }
            flyway(upgrade, "3");
            try (var c = base(); var s = c.createStatement()) {
                s.execute("SET search_path TO " + upgrade);
                try (var r = s.executeQuery("SELECT (SELECT count(*) FROM bo_negocio), (SELECT count(*) FROM bo_negocio_fato),"
                        + " (SELECT quantidade FROM bo_posicao WHERE conta='CONTA-001' AND ticker='ALFA3'),"
                        + " (SELECT count(*) FROM bo_feriado), (" + CheckpointPosicoesJpa.SEM_ORIGEM + ")")) {
                    assertTrue(r.next());
                    assertEquals(1, r.getLong(1), "negócio RF07 preservado");
                    assertEquals(0, r.getLong(2), "sem backfill de fatos");
                    assertEquals(5, r.getLong(3), "posição preservada");
                    assertEquals(18, r.getLong(4));
                    assertTrue(r.getBoolean(5), "guarda detecta RAZAO_SEM_ORIGEM");
                }
            }
        } finally {
            try (var c = base(); var s = c.createStatement()) { s.execute("DROP SCHEMA IF EXISTS " + upgrade + " CASCADE"); }
        }
    }

    @Test
    void calendarioDaMigracaoEOMesmoUsadoNosTestesDeDominio() {
        assertEquals(CalendarioTest.CALENDARIO, razao.calendario());
    }

    @Test
    void consultasPaginamPorContaSemRevelarContraparte() throws Exception {
        List<EventoMatching> eventos = List.of(negocio("ALFA3", DIA, "1", "20.00", 100, "CONTA-001", "CONTA-002"),
                negocio("ALFA3", DIA, "2", "21.00", 2, "CONTA-002", "CONTA-001"),
                negocio("ALFA3", DIA, "3", "22.00", 1, "CONTA-002", "CONTA-002"));
        for (int i = 0; i < eventos.size(); i++) aplicar.aplicar(coordenada(i, eventos.get(i)), eventos.get(i));
        List<ParticipacaoNegocio> pagina = razao.negocios("CONTA-002", Long.MAX_VALUE, 3);
        assertEquals(List.of("ALFA3-2026-10-23-3|VENDEDOR", "ALFA3-2026-10-23-3|COMPRADOR", "ALFA3-2026-10-23-2|COMPRADOR"),
                pagina.stream().map(p -> p.negocio() + "|" + p.lado()).toList());
        List<ParticipacaoNegocio> resto = razao.negocios("CONTA-002", pagina.getLast().sequencia(), 3);
        assertEquals(List.of("ALFA3-2026-10-23-1|VENDEDOR"), resto.stream().map(p -> p.negocio() + "|" + p.lado()).toList());
        ParticipacaoNegocio venda = resto.getFirst();
        assertEquals(new BigDecimal("2000.00"), venda.valor());
        assertEquals(LocalDate.of(2026, 10, 27), venda.liquidacao());
        assertEquals(EstadoLiquidacao.PENDENTE, venda.estado());
        assertEquals(List.of(), razao.negocio("CONTA-003", "ALFA3-2026-10-23-1"));
        assertEquals(List.of(), razao.negocio("CONTA-002", "ALFA3-2026-10-23-9"));
        assertEquals(2, razao.negocio("CONTA-002", "ALFA3-2026-10-23-3").size());
        List<LancamentoRazao> lancamentos = razao.lancamentos("CONTA-001", Long.MAX_VALUE, 100);
        assertEquals(8, lancamentos.size());
        assertEquals(4, razao.lancamentos("CONTA-001", lancamentos.get(3).sequencia(), 100).size());
        assertTrue(lancamentos.stream().allMatch(l -> l.partida().montante() != null));
        assertEquals(ContaContabil.CAMARA, lancamentos.getLast().partida().conta());
        assertEquals(Natureza.DEBITO, lancamentos.getLast().partida().natureza());
        assertEquals(Montante.brl(new BigDecimal("2000.00")), lancamentos.getLast().partida().montante());
        assertEquals(List.of(), razao.lancamentos("CONTA-003", Long.MAX_VALUE, 100));
    }

    private void conferirSemEfeito() throws Exception {
        assertEquals(0, checkpoint.proximo(0));
        for (String tabela : List.of("bo_negocio_fato", "bo_lancamento", "bo_liquidacao", "bo_negocio", "bo_evento", "bo_transporte")) {
            assertEquals(0, numero("SELECT count(*) FROM " + tabela), tabela);
        }
        assertEquals(0, numero("SELECT revisao FROM bo_cotacao WHERE ticker='ALFA3'"));
    }

    private static final String DESBALANCEADAS = """
            SELECT count(*) FROM (SELECT transacao,unidade FROM bo_lancamento GROUP BY transacao,unidade
            HAVING sum(CASE natureza WHEN 'D' THEN coalesce(valor,quantidade) ELSE -coalesce(valor,quantidade) END)<>0) x""";

    private Posicao posicao(String conta) { return posicao(conta, "ALFA3"); }
    private Posicao posicao(String conta, String ticker) {
        return carteiras.consultar(conta).stream().filter(e -> e.ticker().equals(ticker)).findFirst().orElseThrow().posicao();
    }
    private static Coordenada coordenada(long offset, EventoMatching e) { return new Coordenada(TOPICO, 0, offset, e.assinatura()); }
    private static EventoMatching negocio(String ticker, LocalDate dia, String numero, String preco, long quantidade,
                                          String compra, String venda) {
        var c = new EventoMatching.Alvo(UUID.randomUUID(), UUID.randomUUID(), compra);
        var v = new EventoMatching.Alvo(UUID.randomUUID(), UUID.randomUUID(), venda);
        UUID id = UUID.randomUUID();
        return new EventoMatching(id, UUID.nameUUIDFromBytes(("admissao:" + c.intencao()).getBytes(StandardCharsets.UTF_8)),
                ticker, dia, assinatura(id.toString()), new EventoMatching.Negocio(ticker + "-" + dia + "-" + numero,
                new BigDecimal(preco), quantidade, c, v, LadoOrdem.COMPRA));
    }
    private static String assinatura(String valor) { return EnvelopeMatching.assinatura(valor.getBytes(StandardCharsets.UTF_8)); }
    private static void flyway(String schema, String alvo) {
        org.flywaydb.core.Flyway.configure().dataSource(System.getenv("MATCHING_TEST_DB_URL"),
                System.getenv("MATCHING_TEST_DB_USER"), System.getenv("MATCHING_TEST_DB_PASSWORD"))
                .schemas(schema).target(alvo).load().migrate();
    }
    private static long numero(String sql) throws Exception {
        try (var c = conexao(); var s = c.createStatement(); var r = s.executeQuery(sql)) { assertTrue(r.next()); return r.getLong(1); }
    }
    private static List<String> linhas(String sql) throws Exception {
        List<String> resultado = new ArrayList<>();
        try (var c = conexao(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            while (r.next()) resultado.add(r.getString(1));
        }
        return resultado;
    }
    private static Connection base() throws Exception {
        return DriverManager.getConnection(System.getenv("MATCHING_TEST_DB_URL"),
                System.getenv("MATCHING_TEST_DB_USER"), System.getenv("MATCHING_TEST_DB_PASSWORD"));
    }
    private static Connection conexao() throws Exception { Connection c = base(); c.setSchema(SCHEMA); return c; }
}
