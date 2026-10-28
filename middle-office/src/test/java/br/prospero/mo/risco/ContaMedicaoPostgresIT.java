package br.prospero.mo.risco;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V01 (CONTA-005): migração V10 do MO em schema descartável; carteira e custódias nascem zeradas. */
class ContaMedicaoPostgresIT {
    private static final String URL = System.getenv("MATCHING_TEST_DB_URL");
    private static final String USUARIO = System.getenv("MATCHING_TEST_DB_USER");
    private static final String SENHA = System.getenv("MATCHING_TEST_DB_PASSWORD");

    @Test
    void migracaoCriaCarteiraZeradaParaAContaDeMedicao() throws Exception {
        String schema = "mo_conta_medicao_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(URL, USUARIO, SENHA).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load().migrate();
            try (Connection c = conexao(schema); ResultSet r = c.createStatement().executeQuery(
                    "SELECT total, disponivel, reservado FROM conta_risco WHERE conta = 'CONTA-005'")) {
                assertTrue(r.next(), "linha de carteira da CONTA-005");
                assertEquals(0, BigDecimal.ZERO.compareTo(r.getBigDecimal(1)));
                assertEquals(0, BigDecimal.ZERO.compareTo(r.getBigDecimal(2)));
                assertEquals(0, BigDecimal.ZERO.compareTo(r.getBigDecimal(3)));
            }
            try (Connection c = conexao(schema); ResultSet r = c.createStatement().executeQuery(
                    "SELECT (SELECT count(*) FROM custodia_risco WHERE conta = 'CONTA-005' AND total = 0 AND disponivel = 0 "
                            + "AND reservado = 0), (SELECT count(*) FROM ativo_risco)")) {
                assertTrue(r.next());
                assertEquals(r.getInt(2), r.getInt(1), "uma custódia zerada por ativo parametrizado");
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
