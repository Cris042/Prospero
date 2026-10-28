package br.prospero.fo.ordens;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V01 (CONTA-005): migração V13 do FO em schema descartável; conta e linha de revisão da trajetória existem. */
class ContaMedicaoPostgresIT {
    private static final String URL = System.getenv("MATCHING_TEST_DB_URL");
    private static final String USUARIO = System.getenv("MATCHING_TEST_DB_USER");
    private static final String SENHA = System.getenv("MATCHING_TEST_DB_PASSWORD");

    @Test
    void migracaoCadastraAContaDeMedicaoComRevisaoInicial() throws Exception {
        String schema = "fo_conta_medicao_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(URL, USUARIO, SENHA).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load().migrate();
            try (Connection c = conexao(schema); ResultSet r = c.createStatement().executeQuery(
                    "SELECT c.nome, t.revisao FROM conta c JOIN trajetoria_revisao t ON t.conta = c.id WHERE c.id = 'CONTA-005'")) {
                assertTrue(r.next(), "conta CONTA-005 com linha de revisão");
                assertEquals("Conta de medição (RNF04)", r.getString(1));
                assertEquals(0L, r.getLong(2));
            }
        } finally {
            try (Connection c = DriverManager.getConnection(URL, USUARIO, SENHA);
                 var s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static Connection conexao(String schema) throws SQLException {
        Connection c = DriverManager.getConnection(URL, USUARIO, SENHA);
        c.setSchema(schema);
        return c;
    }
}
