package br.prospero.fo.cotacao.redis;

import br.prospero.fo.cotacao.SerializadorCotacaoTest;
import br.prospero.fo.cotacao.dominio.LivroCotado;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-022: Redis cai, uma revisão nova só chega ao PostgreSQL e o Redis volta com a antiga. A primeira operação
 * depois da pausa invalida os snapshots; a leitura nunca devolve a revisão velha como se fosse atual.
 */
class CacheCotacaoRedisRecuperacaoTest {
    private static final LocalDate DIA = LocalDate.of(2026, 10, 28);

    /** Redis em memória: HGET, EVAL de gravação (compare-and-set) e EVAL de invalidação. */
    static final class RedisFalso implements CacheCotacaoRedis.Executor {
        final Map<String, String[]> hashes = new HashMap<>();
        boolean fora;

        @Override
        public String executar(String comando, String... a) {
            if (fora) throw new IllegalStateException("conexão recusada");
            if (comando.equals("HGET")) return hashes.containsKey(a[0]) ? hashes.get(a[0])[1] : null;
            if (a[0].equals(CacheCotacaoRedis.INVALIDAR)) {
                int n = hashes.size();
                hashes.clear();
                return Integer.toString(n);
            }
            String[] atual = hashes.get(a[2]);
            if (atual != null && atual[0].compareTo(a[3]) >= 0) return "0";
            hashes.put(a[2], new String[]{a[3], a[4]});
            return "1";
        }
    }

    @Test
    void voltaDoRedisInvalidaSnapshotsQuePodemTerPerdidoGravacoes() {
        var redis = new RedisFalso();
        var agora = new AtomicLong(0);
        var cache = new CacheCotacaoRedis(redis, new SimpleMeterRegistry(), true, Duration.ofHours(1), Duration.ofSeconds(5), agora::get);
        LivroCotado n = SerializadorCotacaoTest.livro(DIA, 10, "10.10");
        cache.gravar(n);
        assertEquals(n, cache.ler("ALFA3").orElseThrow().livro());

        redis.fora = true;
        cache.gravar(SerializadorCotacaoTest.livro(DIA, 11, "10.20"));
        assertTrue(cache.ler("ALFA3").isEmpty(), "falha vira ausência: o PostgreSQL responde");
        redis.fora = false;
        assertTrue(cache.ler("ALFA3").isEmpty(), "dentro da pausa nem tenta o Redis");
        assertEquals(1, redis.hashes.size(), "o Redis voltou com a revisão 10 guardada");

        agora.addAndGet(Duration.ofSeconds(6).toNanos());
        assertTrue(cache.ler("ALFA3").isEmpty(), "depois da pausa a revisão 10 foi invalidada, não servida");
        assertTrue(redis.hashes.isEmpty());
        LivroCotado n1 = SerializadorCotacaoTest.livro(DIA, 11, "10.20");
        cache.gravar(n1);
        assertEquals(n1, cache.ler("ALFA3").orElseThrow().livro(), "repõe e volta a servir do Redis");
    }

    @Test
    void desabilitadoNaoTocaNoRedis() {
        var redis = new RedisFalso();
        redis.fora = true;
        var cache = new CacheCotacaoRedis(redis, new SimpleMeterRegistry(), false, Duration.ofHours(1), Duration.ofSeconds(5), () -> 0);
        cache.gravar(SerializadorCotacaoTest.livro(DIA, 1, "1.00"));
        assertTrue(cache.ler("ALFA3").isEmpty());
    }
}
