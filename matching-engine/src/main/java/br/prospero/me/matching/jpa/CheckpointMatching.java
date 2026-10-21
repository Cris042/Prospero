package br.prospero.me.matching.jpa;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** Posse única do laboratório; a MESMA conexão física permanece aberta até encerrar o processador. */
public final class CheckpointMatching implements AutoCloseable {
    private static final long CHAVE_POSSE = 57051001L;
    private final Connection conexao;
    private final int backend;
    private boolean fechada;

    public CheckpointMatching(DataSource fonte) throws SQLException {
        conexao = fonte.getConnection();
        try {
            conexao.setAutoCommit(true);
            try (PreparedStatement sql = preparar("SELECT pg_backend_pid(), pg_try_advisory_lock(?)")) {
                sql.setLong(1, CHAVE_POSSE);
                try (ResultSet resultado = sql.executeQuery()) {
                    resultado.next(); backend = resultado.getInt(1);
                    if (!resultado.getBoolean(2)) throw new SQLException("Outra instância possui o matching.");
                }
            }
        } catch (SQLException erro) {
            conexao.close(); throw erro;
        }
    }

    public Map<Integer, Long> inicializar(String comandosId, String eventosId) throws SQLException {
        validarPosse();
        conexao.setAutoCommit(false);
        try {
            try (PreparedStatement sql = preparar("INSERT INTO matching_log(id, topico_comandos_id, topico_eventos_id, origem) VALUES (1, ?, ?, 0) ON CONFLICT (id) DO NOTHING")) {
                sql.setString(1, comandosId); sql.setString(2, eventosId); sql.executeUpdate();
            }
            try (PreparedStatement sql = preparar("SELECT topico_comandos_id, topico_eventos_id, origem FROM matching_log WHERE id = 1");
                 ResultSet r = sql.executeQuery()) {
                if (!r.next() || !comandosId.equals(r.getString(1)) || !eventosId.equals(r.getString(2)) || r.getLong(3) != 0)
                    throw new SQLException("Identidade do log divergente.");
            }
            Map<Integer, Long> offsets = new HashMap<>();
            for (int particao = 0; particao < 3; particao++) {
                try (PreparedStatement sql = preparar("INSERT INTO matching_checkpoint(particao) VALUES (?) ON CONFLICT (particao) DO NOTHING")) {
                    sql.setInt(1, particao); sql.executeUpdate();
                }
                try (PreparedStatement sql = preparar("SELECT ultimo_publicado FROM matching_checkpoint WHERE particao = ?")) {
                    sql.setInt(1, particao);
                    try (ResultSet r = sql.executeQuery()) {
                        if (!r.next()) throw new SQLException("Checkpoint ausente.");
                        offsets.put(particao, r.getLong(1));
                    }
                }
            }
            conexao.commit();
            return Map.copyOf(offsets);
        } catch (SQLException erro) {
            conexao.rollback(); throw erro;
        } finally {
            conexao.setAutoCommit(true);
        }
    }

    public LocalDate sessao() throws SQLException {
        validarPosse();
        try (PreparedStatement sql = preparar("SELECT sessao FROM matching_log WHERE id = 1"); ResultSet r = sql.executeQuery()) {
            if (!r.next()) throw new SQLException("Identidade ausente.");
            return r.getObject(1, LocalDate.class);
        }
    }

    /** Revalida antes de publicar/checkpoint: conexão perdida não permite continuar sobre o motor mutado. */
    public void validarPosse() throws SQLException {
        if (fechada || conexao.isClosed() || !conexao.isValid(5)) throw new SQLException("Posse indisponível.");
        try (PreparedStatement sql = preparar("SELECT pg_backend_pid(), EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory' AND pid = pg_backend_pid() AND classid = 0 AND objid::bigint = ? AND objsubid = 1 AND granted)")) {
            sql.setLong(1, CHAVE_POSSE);
            try (ResultSet r = sql.executeQuery()) {
                if (!r.next() || r.getInt(1) != backend || !r.getBoolean(2)) throw new SQLException("Posse perdida.");
            }
        }
    }

    public void avancar(int particao, long anterior, long novo, LocalDate sessao) throws SQLException {
        if (novo != anterior + 1 || novo > 9999 || sessao == null) throw new SQLException("Avanço inválido.");
        validarPosse();
        conexao.setAutoCommit(false);
        try {
            try (PreparedStatement sql = preparar("UPDATE matching_log SET sessao = ? WHERE id = 1 AND (sessao IS NULL OR sessao = ?)")) {
                sql.setObject(1, sessao); sql.setObject(2, sessao);
                if (sql.executeUpdate() != 1) throw new SQLException("Sessão divergente.");
            }
            try (PreparedStatement sql = preparar("UPDATE matching_checkpoint SET ultimo_publicado = ? WHERE particao = ? AND ultimo_publicado = ?")) {
                sql.setLong(1, novo); sql.setInt(2, particao); sql.setLong(3, anterior);
                if (sql.executeUpdate() != 1) throw new SQLException("CAS do checkpoint recusado.");
            }
            conexao.commit();
        } catch (SQLException erro) {
            conexao.rollback(); throw erro;
        } finally {
            conexao.setAutoCommit(true);
        }
    }

    private PreparedStatement preparar(String texto) throws SQLException {
        PreparedStatement sql = conexao.prepareStatement(texto); sql.setQueryTimeout(5); return sql;
    }

    @Override
    public void close() throws SQLException {
        if (!fechada) {
            fechada = true;
            // Advisory é session-scoped: devolver ao pool sem unlock deixaria um falso proprietário.
            try (PreparedStatement sql = preparar("SELECT pg_advisory_unlock(?)")) {
                sql.setLong(1, CHAVE_POSSE); sql.execute();
            } finally {
                conexao.close();
            }
        }
    }
}
