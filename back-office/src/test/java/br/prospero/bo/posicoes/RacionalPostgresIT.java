package br.prospero.bo.posicoes;

import br.prospero.bo.posicoes.dominio.Racional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Sonda de representação em PostgreSQL descartável, sem tabelas financeiras ou dados de negócio. */
class RacionalPostgresIT {
    private static final String SCHEMA = "bo_racional_qa_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeAll
    static void criarSchemaProprio() throws Exception {
        try (var conexao = conexao(); var sql = conexao.createStatement()) {
            sql.execute("CREATE SCHEMA " + SCHEMA);
            sql.execute("CREATE TABLE " + SCHEMA + ".representacao (id integer PRIMARY KEY, "
                    + "numerador text NOT NULL CHECK (octet_length(numerador) <= 39458), "
                    + "denominador text NOT NULL CHECK (octet_length(denominador) <= 39457))");
        }
    }

    @AfterAll
    static void removerSomenteSchemaProprio() throws Exception {
        try (var conexao = conexao(); var sql = conexao.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void roundtripDoMaiorTamanhoDeclaradoPreservaSinalEDigitos() throws Exception {
        BigInteger base = BigInteger.ONE.shiftLeft(Racional.LIMITE_BITS - 1);
        for (int sinal : new int[]{1, -1}) {
            Racional esperado = Racional.fracao(base.add(BigInteger.ONE).multiply(BigInteger.valueOf(sinal)),
                    base.subtract(BigInteger.ONE));
            try (var conexao = conexao(); var inserir = conexao.prepareStatement(
                    "INSERT INTO " + SCHEMA + ".representacao VALUES (?, ?, ?)")) {
                inserir.setInt(1, sinal);
                inserir.setString(2, esperado.numerador().toString());
                inserir.setString(3, esperado.denominador().toString());
                assertEquals(1, inserir.executeUpdate());
            }
            try (var conexao = conexao(); var consultar = conexao.prepareStatement(
                    "SELECT numerador, denominador FROM " + SCHEMA + ".representacao WHERE id = ?")) {
                consultar.setInt(1, sinal);
                try (var resultado = consultar.executeQuery()) {
                    assertTrue(resultado.next());
                    String numerador = resultado.getString(1), denominador = resultado.getString(2);
                    assertEquals(esperado, Racional.fracao(new BigInteger(numerador), new BigInteger(denominador)));
                    assertTrue(numerador.length() <= 39458);
                    assertTrue(denominador.length() <= 39457);
                    assertFalse(resultado.next());
                    System.out.printf("RF07 PostgreSQL: sinal=%d, numerador=%d caracteres, denominador=%d caracteres íntegros.%n",
                            sinal, numerador.length(), denominador.length());
                }
            }
        }
    }

    private static Connection conexao() throws Exception {
        String url = System.getenv("MATCHING_TEST_DB_URL");
        if (url == null) throw new IllegalStateException("Banco descartável próprio obrigatório para esta sonda.");
        return DriverManager.getConnection(url, System.getenv("MATCHING_TEST_DB_USER"),
                System.getenv("MATCHING_TEST_DB_PASSWORD"));
    }
}
