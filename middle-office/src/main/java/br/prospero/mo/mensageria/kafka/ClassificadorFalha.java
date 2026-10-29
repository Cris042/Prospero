package br.prospero.mo.mensageria.kafka;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Set;

/**
 * Separa falha transitória (banco ou rede fora, timeout, conflito de serialização) de defeito determinístico
 * (ADR-019). Transitória não conta para o limite de tentativas: o consumo espera e repete sem avançar o offset.
 * Percorre a cadeia de causas, porque JTA e Hibernate embrulham a exceção JDBC original.
 */
public final class ClassificadorFalha {
    private static final Set<String> CLASSES = Set.of(
            "org.hibernate.exception.JDBCConnectionException", "org.hibernate.exception.LockAcquisitionException",
            "org.hibernate.exception.LockTimeoutException", "jakarta.persistence.LockTimeoutException",
            "jakarta.persistence.PessimisticLockException", "jakarta.persistence.QueryTimeoutException",
            "java.net.ConnectException", "java.net.SocketTimeoutException", "java.net.SocketException",
            "java.util.concurrent.TimeoutException", "jakarta.transaction.SystemException");

    private ClassificadorFalha() {
    }

    public static boolean transitoria(Throwable erro) {
        Throwable atual = erro;
        for (int profundidade = 0; atual != null && profundidade < 16; profundidade++) {
            if (atual instanceof SQLTransientException || atual instanceof SQLRecoverableException) return true;
            if (atual instanceof org.apache.kafka.common.errors.RetriableException) return true;
            if (atual instanceof SQLException sql && estadoTransitorio(sql.getSQLState())) return true;
            if (CLASSES.contains(atual.getClass().getName())) return true;
            // Agroal sinaliza pool esgotado/banco fora com SQLException sem SQLState.
            String mensagem = atual.getMessage();
            if (atual instanceof SQLException && mensagem != null && mensagem.contains("Acquisition timeout")) return true;
            atual = atual.getCause() == atual ? null : atual.getCause();
        }
        return false;
    }

    /** 08 conexão, 53 recursos, 57P desligamento, 40001 serialização e 40P01 deadlock (PostgreSQL). */
    static boolean estadoTransitorio(String estado) {
        return estado != null && (estado.startsWith("08") || estado.startsWith("53") || estado.startsWith("57P")
                || estado.equals("40001") || estado.equals("40P01"));
    }
}
