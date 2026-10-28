package br.prospero.bo.posicoes;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** V01 (CONTA-005): migração V6 do BO em schema descartável; CHECKs ampliados e posições zeradas da conta de medição. */
class ContaMedicaoPostgresIT {
    private static final String URL = System.getenv("MATCHING_TEST_DB_URL");
    private static final String USUARIO = System.getenv("MATCHING_TEST_DB_USER");
    private static final String SENHA = System.getenv("MATCHING_TEST_DB_PASSWORD");
    private static final String VIOLACAO_CHECK = "23514";

    @Test
    void migracaoAmpliaContasEPosicionaAContaDeMedicao() throws Exception {
        String schema = "bo_conta_medicao_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(URL, USUARIO, SENHA).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load().migrate();
            try (Connection c = conexao(schema)) {
                try (ResultSet r = c.createStatement().executeQuery(
                        "SELECT count(*), count(*) FILTER (WHERE quantidade = 0) FROM bo_posicao WHERE conta = 'CONTA-005'")) {
                    r.next();
                    assertEquals(5, r.getInt(1), "uma posição por ativo cotado");
                    assertEquals(5, r.getInt(2), "posições nascem zeradas");
                }
                try (Statement s = c.createStatement()) {
                    s.execute("INSERT INTO bo_negocio(id, assinatura) VALUES ('neg-medicao', repeat('a', 64))");
                    s.execute("INSERT INTO bo_negocio_fato(negocio_id, ticker, data_sessao, data_liquidacao, preco, quantidade, valor, "
                            + "conta_compra, ordem_compra, conta_venda, ordem_venda) VALUES ('neg-medicao', 'ALFA3', DATE '2026-10-28', "
                            + "DATE '2026-10-30', 10.00, 1, 10.00, 'CONTA-005', '00000000-0000-0000-0000-000000000001', "
                            + "'CONTA-003', '00000000-0000-0000-0000-000000000002')");
                    s.execute("INSERT INTO bo_liquidacao(negocio_id, lado, conta) VALUES ('neg-medicao', 'COMPRADOR', 'CONTA-005')");
                    s.execute("INSERT INTO bo_lancamento(transacao, negocio_id, lado, conta, momento, data_referencia, conta_contabil, "
                            + "natureza, unidade, valor) VALUES (gen_random_uuid(), 'neg-medicao', 'COMPRADOR', 'CONTA-005', 'D0', "
                            + "DATE '2026-10-28', 'CAIXA', 'D', 'BRL', 10.00)");
                }
                assertEquals(VIOLACAO_CHECK, recusado(c, "INSERT INTO bo_posicao(conta, ticker) VALUES ('CONTA-006', 'ALFA3')"));
                assertEquals(VIOLACAO_CHECK, recusado(c, "INSERT INTO bo_liquidacao(negocio_id, lado, conta) "
                        + "VALUES ('neg-medicao', 'VENDEDOR', 'CONTA-006')"));
            }
        } finally {
            try (Connection c = DriverManager.getConnection(URL, USUARIO, SENHA); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static Connection conexao(String schema) throws SQLException {
        Connection c = DriverManager.getConnection(URL, USUARIO, SENHA);
        c.setSchema(schema);
        return c;
    }

    private static String recusado(Connection c, String sql) {
        SQLException erro = assertThrows(SQLException.class, () -> c.createStatement().execute(sql));
        return erro.getSQLState();
    }
}
