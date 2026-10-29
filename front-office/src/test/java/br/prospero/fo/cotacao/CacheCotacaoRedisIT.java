package br.prospero.fo.cotacao;

import br.prospero.fo.cotacao.dominio.CacheCotacao;
import br.prospero.fo.cotacao.dominio.LivroCotado;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-022 contra Redis real (REDIS_TEST_URL/REDIS_TEST_PASSWORD): leitura, compare-and-set e TTL. Opt-in. */
@QuarkusTest
@TestProfile(CacheCotacaoRedisIT.Redis.class)
class CacheCotacaoRedisIT {
    public static class Redis implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("prospero.cotacao.redis.habilitado", "true", "quarkus.redis.hosts", System.getenv("REDIS_TEST_URL"),
                    "quarkus.redis.password", System.getenv().getOrDefault("REDIS_TEST_PASSWORD", ""));
        }
    }

    private static final LocalDate DIA = LocalDate.of(2026, 10, 28);
    @Inject CacheCotacao cache;
    @Inject RedisDataSource redis;

    @BeforeEach
    void limpar() { redis.execute("DEL", "prospero:cotacao:ALFA3"); }

    @Test
    void gravaLeENaoRegrideComEventoRepetidoOuForaDeOrdem() {
        assertTrue(cache.ler("ALFA3").isEmpty());
        cache.gravarAusente("ALFA3");
        assertNull(cache.ler("ALFA3").orElseThrow().livro(), "sem cotação também fica no cache");
        LivroCotado r10 = SerializadorCotacaoTest.livro(DIA, 10, "10.10");
        cache.gravar(r10);
        assertEquals(r10, cache.ler("ALFA3").orElseThrow().livro());
        cache.gravarAusente("ALFA3");
        assertEquals(r10, cache.ler("ALFA3").orElseThrow().livro(), "o ausente nunca apaga snapshot real");
        cache.gravar(SerializadorCotacaoTest.livro(DIA, 9, "9.99"));
        assertEquals(r10, cache.ler("ALFA3").orElseThrow().livro(), "revisão menor (fora de ordem) não regride");
        cache.gravar(SerializadorCotacaoTest.livro(DIA, 10, "11.11"));
        assertEquals(r10, cache.ler("ALFA3").orElseThrow().livro(), "mesma revisão repetida não sobrescreve");
        LivroCotado amanha = SerializadorCotacaoTest.livro(DIA.plusDays(1), 1, "10.20");
        cache.gravar(amanha);
        assertEquals(amanha, cache.ler("ALFA3").orElseThrow().livro(), "sessão nova vence revisão alta da anterior");
        long ttl = redis.execute("PTTL", "prospero:cotacao:ALFA3").toLong();
        assertTrue(ttl > 3_500_000 && ttl <= 3_600_000, "expira em 1 h: " + ttl);
    }

    @Test
    void apiLeDoRedisSemTocarNaProjecao() {
        cache.gravar(SerializadorCotacaoTest.livro(DIA, 7, "10.70"));
        String token = io.smallrye.jwt.build.Jwt.issuer("prospero-local").audience("prospero-front-office")
                .groups("mercado:leitura").expiresIn(300).signWithSecret("chave-sintetica-de-teste-com-mais-de-32-bytes");
        // A projeção em memória está vazia: só o Redis tem a revisão 7.
        io.restassured.RestAssured.given().auth().oauth2(token).get("/api/v1/ativos/ALFA3/livro").then().statusCode(200)
                .header("X-Origem-Dados", "REDIS").body("revisao", org.hamcrest.Matchers.equalTo(7))
                .body("compra.niveis[0].preco", org.hamcrest.Matchers.equalTo("9.90"));
    }
}
