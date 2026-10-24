package br.prospero.bo.liquidacao;

import br.prospero.bo.liquidacao.dominio.MovimentoInvalidoException;
import br.prospero.bo.liquidacao.helper.EventoCredito;
import br.prospero.bo.liquidacao.jpa.LiquidacaoJpa;
import br.prospero.bo.liquidacao.actor.MovimentoActor;
import br.prospero.bo.posicoes.actor.AplicarPosicoesActor;
import br.prospero.bo.posicoes.dominio.Coordenada;
import br.prospero.bo.posicoes.dominio.EventoMatching;
import br.prospero.bo.posicoes.dominio.LadoOrdem;
import br.prospero.bo.posicoes.dominio.SituacaoPosicoes;
import br.prospero.bo.posicoes.helper.EnvelopeMatching;
import br.prospero.bo.posicoes.jpa.CheckpointPosicoesJpa;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** PostgreSQL descartável: data, partidas D2, estado e outbox não admitem confirmação parcial. */
@QuarkusTest
@TestProfile(LiquidacaoPostgresIT.Banco.class)
class LiquidacaoPostgresIT {
    private static final String SCHEMA = schemaDaExecucao();
    private static final String TOPICO = "ABCDEFGHIJKLMNOPQRSTUV";
    private static final LocalDate D0 = LocalDate.of(2026, 10, 23);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 27);
    private static final ObjectMapper JSON = new ObjectMapper();
    @Inject AplicarPosicoesActor aplicar;
    @Inject CheckpointPosicoesJpa checkpoint;
    @Inject LiquidacaoJpa liquidacao;
    @Inject MovimentoActor movimento;

    private static String schemaDaExecucao() {
        synchronized (System.getProperties()) {
            return System.getProperties().computeIfAbsent("prospero.it.bo.liquidacao.schema",
                    chave -> "bo_liquidacao_it_" + UUID.randomUUID().toString().replace("-", "")).toString();
        }
    }

    public static class Banco implements QuarkusTestProfile {
        @Override public Set<Class<?>> getEnabledAlternatives() { return Set.of(Situacao.class); }
        @Override public Map<String, String> getConfigOverrides() {
            try (var c = base(); var s = c.createStatement()) { s.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA); }
            catch (Exception erro) { throw new IllegalStateException("Banco descartável próprio obrigatório", erro); }
            return Map.of("quarkus.datasource.active", "true", "quarkus.hibernate-orm.active", "true",
                    "quarkus.datasource.jdbc.url", System.getenv("MATCHING_TEST_DB_URL") + "?currentSchema=" + SCHEMA,
                    "quarkus.datasource.username", System.getenv("MATCHING_TEST_DB_USER"),
                    "quarkus.datasource.password", System.getenv("MATCHING_TEST_DB_PASSWORD"),
                    "quarkus.datasource.jdbc.max-size", "6",
                    "quarkus.flyway.migrate-at-start", "true", "quarkus.flyway.schemas", SCHEMA);
        }
    }

    @ApplicationScoped @Alternative
    public static class Situacao implements SituacaoPosicoes {
        @Override public boolean emDia() { return true; }
    }

    @BeforeEach void prepararSomenteSchemaDaExecucao() throws Exception {
        executar("DROP TRIGGER IF EXISTS it_falha_outbox ON bo_outbox");
        executar("DROP TRIGGER IF EXISTS it_desbalanceia_d2 ON bo_lancamento");
        executar("DROP TRIGGER IF EXISTS it_constraint_d2 ON bo_lancamento");
        executar("TRUNCATE bo_outbox,bo_movimento_auditoria,bo_data_movimento,bo_liquidacao_controle,bo_negocio_origem,"
                + "bo_lancamento,bo_liquidacao,bo_negocio_fato,bo_transporte,bo_evento,bo_negocio,bo_checkpoint,bo_log");
        executar("UPDATE bo_posicao SET quantidade=0,medio_n=null,medio_d=null,realizado_n='0',realizado_d='1'");
        executar("UPDATE bo_cotacao SET preco=null,sessao=null,encerrada=false,revisao=0,atualizada_em=null");
        executar("INSERT INTO bo_data_movimento(id,data) VALUES(1,DATE '2026-10-23')");
        checkpoint.iniciar(TOPICO);
    }

    @AfterAll static void removerSomenteSchemaDaExecucao() throws Exception {
        try (var c = base(); var s = c.createStatement()) { s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE"); }
    }

    @Test
    void soLiquidaNoVencimentoComPlanoD2BalanceadoECausacaoDoNegocio() throws Exception {
        EventoMatching negocio = negociar(1, "CONTA-001", "CONTA-002");
        assertEquals(List.of(negocio.id().toString()), linhas("SELECT evento::text FROM bo_negocio_origem"));
        assertEquals(0, liquidacao.avancar(D2.minusDays(1), "operador", "antes", D0).liquidacoes());
        assertEquals(2, numero("SELECT count(*) FROM bo_liquidacao WHERE estado='PENDENTE'"));
        assertEquals(0, numero("SELECT count(*) FROM bo_outbox"));
        assertEquals(2, liquidacao.avancar(D2, "operador", "vencimento", D0).liquidacoes());
        assertEquals(List.of("CONTA-001|CUSTODIA|D|ALFA3|100", "CONTA-001|CUSTODIA_A_RECEBER|C|ALFA3|100",
                        "CONTA-002|CAIXA|D|BRL|2000.00", "CONTA-002|CAIXA_A_RECEBER|C|BRL|2000.00"),
                linhas("SELECT conta||'|'||conta_contabil||'|'||natureza||'|'||unidade||'|'||coalesce(valor::text,quantidade::text)"
                        + " FROM bo_lancamento WHERE momento='D2' ORDER BY id"));
        assertEquals(0, numero(DESBALANCEADAS));
        assertEquals(2, numero("SELECT count(*) FROM bo_liquidacao WHERE estado='LIQUIDADO'"));
        assertEquals(2, numero("SELECT count(*) FROM bo_outbox WHERE publicado_em IS NULL AND tentativas=0"));
        assertEquals(0, numero("SELECT count(*) FROM bo_outbox WHERE topico<>'liquidacao.eventos.v1' OR tipo<>'LIQUIDACAO_EFETIVADA'"));
        for (String payload : linhas("SELECT payload FROM bo_outbox")) {
            JsonNode e = JSON.readTree(payload);
            String lado = e.path("dados").path("lado").asText();
            assertEquals(negocio.id().toString(), e.path("causacao").asText());
            assertEquals(EventoCredito.id("ALFA3-2026-10-23-1", lado).toString(), e.path("id").asText());
            assertEquals(D2.toString(), e.path("dados").path("dataLiquidacao").asText());
        }
        assertEquals(0, numero("SELECT count(*) FROM bo_outbox WHERE chave<>(payload::jsonb->'dados'->>'conta')"));
    }

    @Test
    void dataIgualNaoCriaAuditoriaEDataInvalidaNaoMudaEstado() throws Exception {
        negociar(1, "CONTA-001", "CONTA-002");
        assertEquals(0, liquidacao.avancar(D0, "operador", "igual", D0).liquidacoes());
        var retroativa = assertThrows(MovimentoInvalidoException.class,
                () -> liquidacao.avancar(D0.minusDays(1), "operador", "retroativa", D0));
        assertEquals(409, retroativa.status());
        assertEquals("DATA_RETROATIVA", retroativa.codigo());
        assertEquals(422, assertThrows(MovimentoInvalidoException.class,
                () -> liquidacao.avancar(LocalDate.of(2026, 12, 1), "operador", "limite", D0)).status());
        assertEquals(D0, liquidacao.atual());
        assertEquals(0, numero("SELECT count(*) FROM bo_movimento_auditoria"));
        conferirPendentes(2);
    }

    @Test
    void repeticaoDoAvancoEDoWorkerDepoisDoCommitNaoDuplicamMesmoSemRelay() throws Exception {
        negociar(1, "CONTA-001", "CONTA-002");
        liquidacao.avancar(D2, "operador", "unica", D0);
        assertEquals(0, liquidacao.avancar(D2, "operador", "repetida", D0).liquidacoes());
        assertEquals(0, liquidacao.processarLote());
        assertEquals(0, liquidacao.processarLote());
        assertEquals(4, numero("SELECT count(*) FROM bo_lancamento WHERE momento='D2'"));
        assertEquals(2, numero("SELECT count(*) FROM bo_outbox WHERE publicado_em IS NULL"));
        assertEquals(List.of("operador|unica|2026-10-23|2026-10-27|2"),
                linhas("SELECT sub||'|'||jti||'|'||de||'|'||para||'|'||liquidacoes FROM bo_movimento_auditoria"));
        for (String sql : List.of("UPDATE bo_movimento_auditoria SET jti='alterado'", "DELETE FROM bo_movimento_auditoria",
                "UPDATE bo_negocio_origem SET evento=gen_random_uuid()", "DELETE FROM bo_negocio_origem")) {
            try (var c = conexao(); var s = c.createStatement()) { assertThrows(SQLException.class, () -> s.executeUpdate(sql)); }
        }
    }

    @Test
    void loteMaiorQueLimiteRetomaAteZerarSemDuplicar() throws Exception {
        for (int i = 1; i <= 26; i++) negociar(i, "CONTA-001", "CONTA-002");
        assertEquals(50, liquidacao.avancar(D2, "operador", "lotes", D0).liquidacoes());
        assertEquals(2, numero("SELECT count(*) FROM bo_liquidacao WHERE estado='PENDENTE'"));
        assertEquals(2, liquidacao.processarLote());
        assertEquals(0, liquidacao.processarLote());
        assertEquals(52, numero("SELECT count(*) FROM bo_outbox"));
        assertEquals(104, numero("SELECT count(*) FROM bo_lancamento WHERE momento='D2'"));
        assertEquals(0, numero(DESBALANCEADAS));
    }

    @Test
    void falhaNaOutboxReverteDataAuditoriaPartidasEEstadosEPermiteRetomada() throws Exception {
        negociar(1, "CONTA-001", "CONTA-002");
        executar("CREATE FUNCTION it_falhar_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'falha injetada'; END $$");
        executar("CREATE TRIGGER it_falha_outbox BEFORE INSERT ON bo_outbox FOR EACH ROW "
                + "WHEN (NEW.chave='CONTA-002') EXECUTE FUNCTION it_falhar_outbox()");
        assertThrows(RuntimeException.class, () -> liquidacao.avancar(D2, "operador", "falha", D0));
        assertEquals(D0, liquidacao.atual());
        assertEquals(0, numero("SELECT count(*) FROM bo_movimento_auditoria"));
        conferirPendentes(2);
        executar("DROP TRIGGER it_falha_outbox ON bo_outbox");
        assertEquals(2, liquidacao.avancar(D2, "operador", "retomada", D0).liquidacoes());
        assertEquals(2, numero("SELECT count(*) FROM bo_outbox"));
    }

    @Test
    void quedaAntesDoCommitDoLoteReverteTudoEProximaTransacaoRecupera() throws Exception {
        negociar(1, "CONTA-001", "CONTA-002");
        executar("UPDATE bo_data_movimento SET data=DATE '2026-10-27'");
        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(2, liquidacao.processarLote());
            throw new IllegalStateException("queda técnica antes do commit");
        }));
        conferirPendentes(2);
        assertEquals(2, liquidacao.processarLote());
        assertEquals(0, liquidacao.processarLote());
        assertEquals(2, numero("SELECT count(*) FROM bo_outbox"));
    }

    @Test
    void avancosConcorrentesParaMesmaDataTemUmaAuditoriaEUmCreditoPorLado() throws Exception {
        negociar(1, "CONTA-001", "CONTA-002");
        var barreira = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barreira.await(10, TimeUnit.SECONDS); return liquidacao.avancar(D2, "operador", "a", D0); });
            var b = executor.submit(() -> { barreira.await(10, TimeUnit.SECONDS); return liquidacao.avancar(D2, "operador", "b", D0); });
            assertEquals(2, a.get(30, TimeUnit.SECONDS).liquidacoes() + b.get(30, TimeUnit.SECONDS).liquidacoes());
        }
        assertEquals(1, numero("SELECT count(*) FROM bo_movimento_auditoria"));
        assertEquals(2, numero("SELECT count(*) FROM bo_outbox"));
        assertEquals(4, numero("SELECT count(*) FROM bo_lancamento WHERE momento='D2'"));
    }

    @Test
    void autonegociacaoLiquidaOsDoisLadosParaMesmaContaSemConfundirIdentidades() throws Exception {
        negociar(1, "CONTA-002", "CONTA-002");
        assertEquals(2, liquidacao.avancar(D2, "operador", "self", D0).liquidacoes());
        assertEquals(2, numero("SELECT count(DISTINCT id) FROM bo_outbox WHERE chave='CONTA-002'"));
        assertEquals(0, numero(DESBALANCEADAS));
        assertEquals(0, numero("SELECT count(*) FROM (SELECT unidade FROM bo_lancamento GROUP BY unidade HAVING "
                + "sum(CASE natureza WHEN 'D' THEN coalesce(valor,quantidade) ELSE -coalesce(valor,quantidade) END)<>0) x"));
    }

    @Test
    void identidadeDeAuditoriaAusenteOuInvalidaERecusadaAntesDeEscrita() throws Exception {
        for (String[] identidade : new String[][]{{null, "jti"}, {"operador", null}, {"", "jti"}, {"operador", "\n"},
                {"x".repeat(101), "jti"}, {"operador", "x".repeat(101)}}) {
            assertEquals(403, assertThrows(MovimentoInvalidoException.class,
                    () -> liquidacao.avancar(D2, identidade[0], identidade[1], D0)).status());
        }
        assertEquals(D0, liquidacao.atual());
        assertEquals(0, numero("SELECT count(*) FROM bo_movimento_auditoria"));
    }

    @Test
    void quarentenaPersistidaRecusaNovosLotesEAvancosSemNovaTentativa() throws Exception {
        negociar(1, "CONTA-001", "CONTA-002");
        liquidacao.quarentena();
        assertEquals(503, assertThrows(MovimentoInvalidoException.class, liquidacao::processarLote).status());
        assertEquals(503, assertThrows(MovimentoInvalidoException.class,
                () -> liquidacao.avancar(D2, "operador", "bloqueado", D0)).status());
        conferirPendentes(2);
        assertEquals(D0, liquidacao.atual());
        String token = Jwt.issuer("prospero-local").audience("prospero-back-office").subject("CONTA-001")
                .groups(Set.of("razao:leitura")).expiresIn(300).signWithSecret(MovimentoResourceTest.CHAVE);
        for (String rota : List.of("/api/v1/razao", "/api/v1/negocios", "/api/v1/negocios/ALFA3-2026-10-23-1")) {
            given().auth().oauth2(token).get(rota).then().statusCode(503)
                    .body("codigo", equalTo("RAZAO_INDISPONIVEL"));
        }
    }

    @Test
    void origemAusenteFalhaFechadoReverteEQuarentenaFicaPersistida() throws Exception {
        negociar(1, "CONTA-001", "CONTA-002");
        // Simula uma pendência RF08b anterior à captura da origem; não inventa backfill.
        executar("ALTER TABLE bo_negocio_origem DISABLE TRIGGER bo_origem_append_only");
        executar("DELETE FROM bo_negocio_origem");
        executar("ALTER TABLE bo_negocio_origem ENABLE TRIGGER bo_origem_append_only");
        assertEquals(503, assertThrows(MovimentoInvalidoException.class,
                () -> movimento.avancar(D2, "operador", "origem-ausente")).status());
        assertEquals(1, numero("SELECT count(*) FROM bo_liquidacao_controle"));
        conferirPendentes(2);
        assertEquals(D0, liquidacao.atual());
        assertEquals(0, numero("SELECT count(*) FROM bo_movimento_auditoria"));
        assertEquals(503, assertThrows(MovimentoInvalidoException.class, liquidacao::processarLote).status());
    }

    @Test
    void invarianteContabilVioladaNoD2ReverteEQuarentenaSemRepeticao() throws Exception {
        negociar(1, "CONTA-001", "CONTA-002");
        executar("CREATE FUNCTION it_desbalancear_d2() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.valor := NEW.valor + 0.01; RETURN NEW; END $$");
        executar("CREATE TRIGGER it_desbalanceia_d2 BEFORE INSERT ON bo_lancamento FOR EACH ROW "
                + "WHEN (NEW.momento='D2' AND NEW.conta_contabil='CAIXA') EXECUTE FUNCTION it_desbalancear_d2()");
        assertEquals(503, assertThrows(MovimentoInvalidoException.class,
                () -> movimento.avancar(D2, "operador", "invariante-d2")).status());
        assertEquals(1, numero("SELECT count(*) FROM bo_liquidacao_controle"));
        conferirPendentes(2);
        assertEquals(D0, liquidacao.atual());
        assertEquals(503, assertThrows(MovimentoInvalidoException.class, liquidacao::processarLote).status());
    }

    @Test
    void constraintDoPostgresNoD2EQuarentenaTecnicaSemCreditoParcial() throws Exception {
        negociar(1, "CONTA-001", "CONTA-002");
        executar("CREATE FUNCTION it_constraint_d2() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.valor := 0; RETURN NEW; END $$");
        executar("CREATE TRIGGER it_constraint_d2 BEFORE INSERT ON bo_lancamento FOR EACH ROW "
                + "WHEN (NEW.momento='D2' AND NEW.conta_contabil='CAIXA') EXECUTE FUNCTION it_constraint_d2()");
        assertEquals(503, assertThrows(MovimentoInvalidoException.class,
                () -> movimento.avancar(D2, "operador", "constraint-d2")).status());
        assertEquals(1, numero("SELECT count(*) FROM bo_liquidacao_controle"));
        conferirPendentes(2);
        assertEquals(D0, liquidacao.atual());
    }

    @Test
    void avancoAlemDoVencimentoUsaDataDeLiquidacaoDoFatoNosLancamentosEEvento() throws Exception {
        negociar(1, "CONTA-001", "CONTA-002");
        assertEquals(2, liquidacao.avancar(D2.plusDays(1), "operador", "apos-vencimento", D0).liquidacoes());
        assertEquals(List.of(D2.toString()), linhas("SELECT DISTINCT data_referencia::text FROM bo_lancamento WHERE momento='D2'"));
        assertEquals(List.of(D2.toString()), linhas("SELECT DISTINCT payload::jsonb->'dados'->>'dataLiquidacao' FROM bo_outbox"));
        assertEquals(D2.plusDays(1), liquidacao.atual());
    }

    private void conferirPendentes(int quantidade) throws Exception {
        assertEquals(quantidade, numero("SELECT count(*) FROM bo_liquidacao WHERE estado='PENDENTE'"));
        assertEquals(0, numero("SELECT count(*) FROM bo_lancamento WHERE momento='D2'"));
        assertEquals(0, numero("SELECT count(*) FROM bo_outbox"));
    }

    private EventoMatching negociar(int numero, String compra, String venda) {
        UUID id = UUID.randomUUID();
        var c = new EventoMatching.Alvo(UUID.randomUUID(), UUID.randomUUID(), compra);
        var v = new EventoMatching.Alvo(UUID.randomUUID(), UUID.randomUUID(), venda);
        String assinatura = EnvelopeMatching.assinatura(id.toString().getBytes(StandardCharsets.UTF_8));
        var evento = new EventoMatching(id, UUID.nameUUIDFromBytes(("admissao:" + c.intencao()).getBytes(StandardCharsets.UTF_8)),
                "ALFA3", D0, assinatura, new EventoMatching.Negocio("ALFA3-2026-10-23-" + numero,
                new BigDecimal("20.00"), 100, c, v, LadoOrdem.COMPRA));
        aplicar.aplicar(new Coordenada(TOPICO, 0, numero - 1, assinatura), evento);
        return evento;
    }

    private static final String DESBALANCEADAS = "SELECT count(*) FROM (SELECT transacao,unidade FROM bo_lancamento "
            + "GROUP BY transacao,unidade HAVING sum(CASE natureza WHEN 'D' THEN coalesce(valor,quantidade) "
            + "ELSE -coalesce(valor,quantidade) END)<>0) x";
    private static void executar(String sql) throws Exception {
        try (var c = conexao(); var s = c.createStatement()) { s.execute(sql); }
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
