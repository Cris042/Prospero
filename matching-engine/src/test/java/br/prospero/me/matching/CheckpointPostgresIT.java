package br.prospero.me.matching;

import br.prospero.me.matching.jpa.CheckpointMatching;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integração opt-in: -Dtest=CheckpointPostgresIT com MATCHING_TEST_DB_URL/USER/PASSWORD para banco descartável.
 * Não integra a suíte sem infraestrutura. Cria e remove somente schema aleatório de propriedade deste teste.
 */
class CheckpointPostgresIT {
    @Test
    void posseExclusivaCheckpointCasIdentidadeESessaoPersistem() throws Exception {
        PGSimpleDataSource fonte = new PGSimpleDataSource();
        fonte.setUrl(Objects.requireNonNull(System.getenv("MATCHING_TEST_DB_URL"), "MATCHING_TEST_DB_URL obrigatório"));
        fonte.setUser(Objects.requireNonNull(System.getenv("MATCHING_TEST_DB_USER"), "MATCHING_TEST_DB_USER obrigatório"));
        fonte.setPassword(Objects.requireNonNull(System.getenv("MATCHING_TEST_DB_PASSWORD"), "MATCHING_TEST_DB_PASSWORD obrigatório"));
        String schema = "matching_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = fonte.getConnection(); var s = c.createStatement()) { s.execute("CREATE SCHEMA " + schema); }
        fonte.setCurrentSchema(schema);
        try {
            try (var c = fonte.getConnection(); var s = c.createStatement();
                 var migration = getClass().getResourceAsStream("/db/migration/V2__checkpoint_matching.sql")) {
                s.execute(new String(Objects.requireNonNull(migration).readAllBytes(), StandardCharsets.UTF_8));
            }
            String comandos = UUID.randomUUID().toString(), eventos = UUID.randomUUID().toString();
            LocalDate sessao = LocalDate.of(2026, 10, 18);
            try (CheckpointMatching primeira = new CheckpointMatching(fonte);
                 var concorrente = Executors.newSingleThreadExecutor()) {
                assertTrue(concorrente.submit(() -> {
                    try (CheckpointMatching segunda = new CheckpointMatching(fonte)) { return false; }
                    catch (SQLException erro) { return true; }
                }).get(10, TimeUnit.SECONDS), "Segunda instância não pode tomar posse.");
                assertEquals(-1L, primeira.inicializar(comandos, eventos).get(0));
                assertNull(primeira.sessao());
                primeira.avancar(0, -1, 0, sessao);
                assertThrows(SQLException.class, () -> primeira.avancar(0, -1, 0, sessao));
                assertThrows(SQLException.class, () -> primeira.avancar(1, -1, 0, sessao.plusDays(1)));
                assertEquals(sessao, primeira.sessao());
            }
            try (CheckpointMatching reinicio = new CheckpointMatching(fonte)) {
                assertEquals(0L, reinicio.inicializar(comandos, eventos).get(0));
                assertEquals(-1L, reinicio.inicializar(comandos, eventos).get(1));
                assertThrows(SQLException.class, () -> reinicio.inicializar(UUID.randomUUID().toString(), eventos));
                reinicio.validarPosse();
            }
        } finally {
            fonte.setCurrentSchema("public");
            try (var c = fonte.getConnection(); var s = c.createStatement()) { s.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
}
