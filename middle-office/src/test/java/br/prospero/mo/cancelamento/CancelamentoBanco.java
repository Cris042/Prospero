package br.prospero.mo.cancelamento;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Perfil dos ITs de cancelamento: schema próprio no banco descartável informado por MATCHING_TEST_DB_*. */
public class CancelamentoBanco implements QuarkusTestProfile {
    private static final String SCHEMA = schemaDaExecucao();

    private static String schemaDaExecucao() {
        // Quarkus usa classloaders distintos para perfil e teste; a propriedade mantém a mesma identidade.
        synchronized (System.getProperties()) {
            return System.getProperties().computeIfAbsent("prospero.it.mo.cancelamento.schema",
                    chave -> "mo_cancelamento_it_" + UUID.randomUUID().toString().replace("-", "")).toString();
        }
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        try (var c = conexao(); var s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
        } catch (Exception erro) {
            throw new IllegalStateException("Banco descartável obrigatório", erro);
        }
        // Os dublês em memória dos demais testes não valem aqui: outbox, risco e rejeições são os adaptadores JPA reais.
        // Propriedade de build: o perfil provoca nova augmentation, sem afetar os testes sem banco.
        return Map.of("quarkus.arc.exclude-types",
                "br.prospero.mo.mensageria.MensageriaMemoria,br.prospero.mo.risco.RepositorioRiscoMemoria,br.prospero.mo.sessao.RepositorioSessaoMemoria",
                "quarkus.datasource.active", "true", "quarkus.datasource.jdbc.url",
                System.getenv("MATCHING_TEST_DB_URL") + "?currentSchema=" + SCHEMA,
                "quarkus.datasource.username", System.getenv("MATCHING_TEST_DB_USER"),
                "quarkus.datasource.password", System.getenv("MATCHING_TEST_DB_PASSWORD"),
                "quarkus.flyway.migrate-at-start", "true", "quarkus.flyway.schemas", SCHEMA);
    }

    private static java.sql.Connection conexao() throws Exception {
        return DriverManager.getConnection(System.getenv("MATCHING_TEST_DB_URL"),
                System.getenv("MATCHING_TEST_DB_USER"), System.getenv("MATCHING_TEST_DB_PASSWORD"));
    }

    public static void removerSchemaProprio() throws Exception {
        try (var c = conexao(); var s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            try (var consulta = c.prepareStatement("SELECT 1 FROM pg_namespace WHERE nspname = ?")) {
                consulta.setString(1, SCHEMA);
                try (var resultado = consulta.executeQuery()) { assertFalse(resultado.next(), "schema próprio removido"); }
            }
        }
    }
}
