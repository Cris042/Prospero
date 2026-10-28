package br.prospero.mo.cancelamento;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RF10c2b: migração real 8→9 sobre dados existentes, sem tocar V1–V8, e CHECKs da tabela de coordenação. */
class CancelamentoMigracaoPostgresIT {
    private static Connection conexao() throws Exception {
        return DriverManager.getConnection(System.getenv("MATCHING_TEST_DB_URL"), System.getenv("MATCHING_TEST_DB_USER"), System.getenv("MATCHING_TEST_DB_PASSWORD"));
    }

    private static Flyway flyway(String schema, String alvo) {
        return Flyway.configure().dataSource(System.getenv("MATCHING_TEST_DB_URL"), System.getenv("MATCHING_TEST_DB_USER"), System.getenv("MATCHING_TEST_DB_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target(alvo).load();
    }

    private interface Cenario { void executar(Connection c, UUID intencao) throws Exception; }

    private void banco(Cenario cenario) throws Exception {
        String schema = "qa_migracao_cancelamento_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = conexao(); var s = c.createStatement()) {
            flyway(schema, "8").migrate();
            s.execute("SET search_path TO " + schema);
            UUID intencao = UUID.randomUUID();
            try (var p = c.prepareStatement("INSERT INTO decisao_risco(intencao,evento,conta,ticker,lado,resultado,quantidade_reservada,versao_parametros,decidida_em) VALUES(?,?,'CONTA-001','ALFA3','VENDA','RESERVADA',10,1,now())")) {
                p.setObject(1, intencao);
                p.setObject(2, UUID.randomUUID());
                p.executeUpdate();
            }
            try (var p = c.prepareStatement("INSERT INTO reserva(intencao,conta,ticker,recurso,quantidade,estado,criada_em) VALUES(?,'CONTA-001','ALFA3','CUSTODIA',10,'ATIVA',now())")) {
                p.setObject(1, intencao);
                p.executeUpdate();
            }
            flyway(schema, "9").migrate();
            cenario.executar(c, intencao);
        } finally {
            try (var c = conexao(); var s = c.createStatement()) { s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
        }
    }

    private static long total(Connection c, String tabela) throws Exception {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM " + tabela)) {
            r.next();
            return r.getLong(1);
        }
    }

    /** Insere uma operação com os campos sobrescritos; valores padrão formam uma linha AGUARDANDO_ACK válida. */
    private static void inserir(Connection c, UUID intencao, String estado, String terminal, String decisao, String motivo,
                                String decidida, String assinatura, UUID solicitacao) throws Exception {
        UUID ordem = UUID.randomUUID();
        try (var p = c.prepareStatement("""
                INSERT INTO cancelamento_operacao(ordem,intencao,admissao,conta,ticker,data_sessao,solicitacao,comando,assinatura,
                estado,terminal,decisao,motivo,decidida_em)
                VALUES(?,?,?,'CONTA-001','ALFA3','2026-10-18',?,?,?,?,CAST(? AS uuid),?,?,CAST(? AS timestamptz))""")) {
            p.setObject(1, ordem);
            p.setObject(2, intencao);
            p.setObject(3, UUID.randomUUID());
            p.setObject(4, solicitacao);
            p.setObject(5, UUID.randomUUID());
            p.setString(6, assinatura);
            p.setString(7, estado);
            p.setString(8, terminal);
            p.setString(9, decisao);
            p.setString(10, motivo);
            p.setString(11, decidida);
            p.executeUpdate();
        }
    }

    private static final String ASSINATURA = "a".repeat(64);

    @Test
    void migracaoAplicaSobreDadosExistentesECriaTabelaVazia() throws Exception {
        banco((c, intencao) -> {
            assertEquals(1, total(c, "reserva"));
            assertEquals(1, total(c, "decisao_risco"));
            assertEquals(0, total(c, "cancelamento_operacao"));
            inserir(c, intencao, "AGUARDANDO_ACK", null, null, null, null, ASSINATURA, UUID.randomUUID());
            assertEquals(1, total(c, "cancelamento_operacao"));
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7','8','9') AND success")) {
                r.next();
                assertEquals(9, r.getInt(1));
            }
        });
    }

    @Test
    void linhasCoerentesDeCadaEstadoSaoAceitas() throws Exception {
        banco((c, intencao) -> {
            String terminal = UUID.randomUUID().toString();
            inserir(c, intencao, "COMANDO_EMITIDO", null, null, null, null, ASSINATURA, UUID.randomUUID());
            inserir(c, intencao, "TERMINAL_APLICADO", terminal, null, null, null, ASSINATURA, UUID.randomUUID());
            inserir(c, intencao, "CONFIRMADO", terminal, "APLICADO", null, "2026-10-18T12:00:00Z", ASSINATURA, UUID.randomUUID());
            inserir(c, intencao, "RECUSADO", null, null, "RISCO_REJEITADO", "2026-10-18T12:00:00Z", ASSINATURA, UUID.randomUUID());
            inserir(c, intencao, "RECUSADO", null, "SEM_SALDO_VIVO", "SEM_SALDO_VIVO", "2026-10-18T12:00:00Z", ASSINATURA, UUID.randomUUID());
            assertEquals(5, total(c, "cancelamento_operacao"));
        });
    }

    @Test
    void checksRejeitamEstadoMotivoTerminalDecisaoEAssinaturaIncoerentes() throws Exception {
        banco((c, intencao) -> {
            String terminal = UUID.randomUUID().toString(), agora = "2026-10-18T12:00:00Z";
            List<Runnable> invalidas = List.of(
                    () -> tentar(c, intencao, "FINALIZADO", null, null, null, null, ASSINATURA),
                    () -> tentar(c, intencao, "COMANDO_EMITIDO", null, null, "ORIGEM_OCO", null, ASSINATURA),
                    () -> tentar(c, intencao, "RECUSADO", null, null, null, agora, ASSINATURA),
                    () -> tentar(c, intencao, "RECUSADO", null, null, "MOTIVO_NOVO", agora, ASSINATURA),
                    () -> tentar(c, intencao, "COMANDO_EMITIDO", terminal, null, null, null, ASSINATURA),
                    () -> tentar(c, intencao, "TERMINAL_APLICADO", null, null, null, null, ASSINATURA),
                    () -> tentar(c, intencao, "CONFIRMADO", null, "APLICADO", null, agora, ASSINATURA),
                    () -> tentar(c, intencao, "CONFIRMADO", terminal, "SEM_SALDO_VIVO", null, agora, ASSINATURA),
                    () -> tentar(c, intencao, "CONFIRMADO", terminal, null, null, agora, ASSINATURA),
                    () -> tentar(c, intencao, "COMANDO_EMITIDO", null, "APLICADO", null, null, ASSINATURA),
                    () -> tentar(c, intencao, "RECUSADO", null, "APLICADO", "SEM_SALDO_VIVO", agora, ASSINATURA),
                    () -> tentar(c, intencao, "RECUSADO", null, "ORIGEM_OCO", "SEM_SALDO_VIVO", agora, ASSINATURA),
                    () -> tentar(c, intencao, "RECUSADO", null, "DESCONHECIDA", "SEM_SALDO_VIVO", agora, ASSINATURA),
                    () -> tentar(c, intencao, "COMANDO_EMITIDO", null, null, null, agora, ASSINATURA),
                    () -> tentar(c, intencao, "AGUARDANDO_ACK", null, null, null, null, "g".repeat(64)),
                    () -> tentar(c, intencao, "AGUARDANDO_ACK", null, null, null, null, "a".repeat(63)));
            for (int i = 0; i < invalidas.size(); i++) {
                int caso = i;
                assertDoesNotThrow(() -> invalidas.get(caso).run(), "caso " + caso);
            }
            assertEquals(0, total(c, "cancelamento_operacao"));
        });
    }

    private static void tentar(Connection c, UUID intencao, String estado, String terminal, String decisao, String motivo,
                               String decidida, String assinatura) {
        var erro = assertThrows(java.sql.SQLException.class,
                () -> inserir(c, intencao, estado, terminal, decisao, motivo, decidida, assinatura, UUID.randomUUID()),
                estado + "/" + terminal + "/" + decisao + "/" + motivo);
        assertNotNull(erro);
    }

    @Test
    void unicidadeDaSolicitacaoEChaveEstrangeiraDaIntencao() throws Exception {
        banco((c, intencao) -> {
            UUID solicitacao = UUID.randomUUID();
            inserir(c, intencao, "AGUARDANDO_ACK", null, null, null, null, ASSINATURA, solicitacao);
            assertThrows(java.sql.SQLException.class, () -> inserir(c, intencao, "AGUARDANDO_ACK", null, null, null, null, ASSINATURA, solicitacao));
            assertThrows(java.sql.SQLException.class, () -> inserir(c, UUID.randomUUID(), "AGUARDANDO_ACK", null, null, null, null, ASSINATURA, UUID.randomUUID()));
            assertEquals(1, total(c, "cancelamento_operacao"));
        });
    }
}
