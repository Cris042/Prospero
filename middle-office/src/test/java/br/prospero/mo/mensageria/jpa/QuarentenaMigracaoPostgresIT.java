package br.prospero.mo.mensageria.jpa;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Migração real 10→11 (ADR-019) em schema próprio: linhas antigas sem payload, posição repetida vira uma. Opt-in. */
class QuarentenaMigracaoPostgresIT {
    private static Connection conexao() throws Exception {
        return DriverManager.getConnection(System.getenv("MATCHING_TEST_DB_URL"), System.getenv("MATCHING_TEST_DB_USER"),
                System.getenv("MATCHING_TEST_DB_PASSWORD"));
    }

    private static Flyway flyway(String schema, String alvo) {
        return Flyway.configure().dataSource(System.getenv("MATCHING_TEST_DB_URL"), System.getenv("MATCHING_TEST_DB_USER"),
                System.getenv("MATCHING_TEST_DB_PASSWORD")).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target(alvo).load();
    }

    @Test
    void preservaLinhasAntigasSemPayloadEDeduplicaAPosicao() throws Exception {
        String schema = "qa_quarentena_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = conexao(); var s = c.createStatement()) {
            flyway(schema, "10").migrate();
            s.execute("SET search_path TO " + schema);
            s.execute("""
                    INSERT INTO evento_rejeitado (topico, particao, deslocamento, motivo, recebido_em) VALUES
                    ('risco.comandos.v1', 0, 5, 'json', '2026-10-27T10:00:00Z'),
                    ('risco.comandos.v1', 0, 5, 'json', '2026-10-27T10:00:01Z'),
                    ('risco.comandos.v1', 1, 5, 'chave', '2026-10-27T10:00:02Z')""");
            flyway(schema, "11").migrate();
            try (var r = s.executeQuery("""
                    SELECT count(*), count(payload), min(quarentenado_em)::text, bool_and(tentativas = 1),
                        bool_and(resolvido_em IS NULL) FROM mensagem_quarentena""")) {
                assertTrue(r.next());
                assertEquals(2, r.getLong(1), "a posição repetida fica uma vez só");
                assertEquals(0, r.getLong(2), "linha antiga não ganha payload inventado");
                assertTrue(r.getString(3).startsWith("2026-10-27 10:00:00"), "a mais antiga é preservada");
                assertTrue(r.getBoolean(4));
                assertTrue(r.getBoolean(5), "linhas antigas continuam pendentes até o descarte");
            }
            var erro = assertThrows(java.sql.SQLException.class, () -> s.execute("""
                    INSERT INTO mensagem_quarentena (topico, particao, deslocamento, motivo, quarentenado_em)
                    VALUES ('risco.comandos.v1', 0, 5, 'json', now())"""));
            assertEquals("23505", erro.getSQLState(), "posição única");
            var resolucao = assertThrows(java.sql.SQLException.class, () -> s.execute(
                    "UPDATE mensagem_quarentena SET resolucao = 'DESCARTADA' WHERE particao = 1"));
            assertEquals("23514", resolucao.getSQLState(), "resolução sem horário é recusada");
        } finally {
            try (var c = conexao(); var s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
