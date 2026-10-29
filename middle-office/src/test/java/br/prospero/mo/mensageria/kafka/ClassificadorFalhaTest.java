package br.prospero.mo.mensageria.kafka;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-019: só falha de infraestrutura é transitória; defeito determinístico conta tentativa. */
class ClassificadorFalhaTest {
    @Test
    void bancoRecusandoConexaoETransitorioPeloDriverReal() {
        // Porta 1 sem PostgreSQL: o driver real devolve SQLState 08001, como num banco fora do ar.
        var erro = assertThrows(SQLException.class,
                () -> DriverManager.getConnection("jdbc:postgresql://127.0.0.1:1/prospero?connectTimeout=1", "x", "x"));
        assertTrue(ClassificadorFalha.transitoria(new RuntimeException("jta", erro)), erro.getSQLState());
    }

    @Test
    void classificaPelaCadeiaDeCausasEPeloSqlState() {
        assertTrue(ClassificadorFalha.transitoria(new IllegalStateException(new SQLTransientConnectionException("fora"))));
        assertTrue(ClassificadorFalha.transitoria(new RuntimeException(new SQLException("x", "40001"))));
        assertTrue(ClassificadorFalha.transitoria(new RuntimeException(new SQLException("x", "57P01"))));
        assertTrue(ClassificadorFalha.transitoria(new RuntimeException(new SQLException("Acquisition timeout while waiting"))));
        assertTrue(ClassificadorFalha.transitoria(new RuntimeException(new java.net.ConnectException("recusada"))));
        assertTrue(ClassificadorFalha.transitoria(new org.apache.kafka.common.errors.TimeoutException("kafka")));
        assertFalse(ClassificadorFalha.transitoria(new IllegalStateException("regra")));
        assertFalse(ClassificadorFalha.transitoria(new RuntimeException(new SQLException("x", "23505"))), "unicidade é permanente");
        assertFalse(ClassificadorFalha.transitoria(new IllegalArgumentException("contrato")));
    }

    @Test
    void cadeiaCiclicaTermina() {
        var a = new RuntimeException("a");
        var b = new RuntimeException("b", a);
        a.initCause(b);
        assertFalse(ClassificadorFalha.transitoria(a));
    }
}
