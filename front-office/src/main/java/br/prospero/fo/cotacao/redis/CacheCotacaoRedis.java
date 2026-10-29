package br.prospero.fo.cotacao.redis;

import br.prospero.fo.cotacao.dominio.CacheCotacao;
import br.prospero.fo.cotacao.dominio.CotacaoEmCache;
import br.prospero.fo.cotacao.dominio.LivroCotado;
import br.prospero.fo.cotacao.helper.SerializadorCotacao;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;

/**
 * Snapshot por ativo no Redis (ADR-022): hash {@code prospero:cotacao:<ticker>} com versão e JSON, gravado por Lua
 * com compare-and-set. Falha abre uma janela de {@code pausa} sem tentar o Redis, para a leitura não pagar o timeout
 * a cada pedido; o PostgreSQL responde nesse intervalo.
 */
@ApplicationScoped
public class CacheCotacaoRedis implements CacheCotacao {
    private static final Logger LOG = Logger.getLogger(CacheCotacaoRedis.class);
    static final String GRAVAR = """
            local v = redis.call('HGET', KEYS[1], 'v')
            if v and v >= ARGV[1] then return 0 end
            redis.call('HSET', KEYS[1], 'v', ARGV[1], 'd', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1""";
    private final Instance<RedisDataSource> redis;
    private final boolean habilitado;
    private final long ttlMs;
    private final long pausaNanos;
    private final MeterRegistry metricas;
    private volatile boolean pausado;
    private volatile long pausadoAte;

    @Inject
    public CacheCotacaoRedis(Instance<RedisDataSource> redis, MeterRegistry metricas,
                             @ConfigProperty(name = "prospero.cotacao.redis.habilitado", defaultValue = "false") boolean habilitado,
                             @ConfigProperty(name = "prospero.cotacao.redis.ttl", defaultValue = "PT1H") Duration ttl,
                             @ConfigProperty(name = "prospero.cotacao.redis.pausa", defaultValue = "PT5S") Duration pausa) {
        this.redis = redis;
        this.metricas = metricas;
        this.habilitado = habilitado;
        this.ttlMs = ttl.toMillis();
        this.pausaNanos = pausa.toNanos();
    }

    static String chave(String ticker) { return "prospero:cotacao:" + ticker; }

    @Override
    public Optional<CotacaoEmCache> ler(String ticker) {
        if (!disponivel()) return Optional.empty();
        try {
            var resposta = redis.get().execute("HGET", chave(ticker), "d");
            if (resposta == null) return Optional.empty();
            String texto = resposta.toString();
            return Optional.of(new CotacaoEmCache(SerializadorCotacao.ausente(texto) ? null : SerializadorCotacao.ler(texto)));
        } catch (RuntimeException erro) {
            falhou("leitura", erro);
            return Optional.empty();
        }
    }

    @Override
    public void gravar(LivroCotado livro) {
        gravar(livro.sessao().ticker(), SerializadorCotacao.versao(livro.sessao()), SerializadorCotacao.serializar(livro));
    }

    @Override
    public void gravarAusente(String ticker) {
        gravar(ticker, SerializadorCotacao.VERSAO_AUSENTE, SerializadorCotacao.AUSENTE);
    }

    private void gravar(String ticker, String versao, String dados) {
        if (!disponivel()) return;
        try {
            // Lua compara com >=: o "ausente" (versão zero) nunca sobrescreve snapshot real nem se repete.
            var resposta = redis.get().execute("EVAL", GRAVAR, "1", chave(ticker), versao, dados, Long.toString(ttlMs));
            metricas.counter("prospero.cotacao.redis.gravacoes", "resultado",
                    resposta != null && resposta.toLong() == 1 ? "gravada" : "ignorada").increment();
        } catch (RuntimeException erro) {
            falhou("gravacao", erro);
        }
    }

    private boolean disponivel() {
        if (!habilitado) return false;
        if (pausado && System.nanoTime() - pausadoAte < 0) return false;
        pausado = false;
        return true;
    }

    private void falhou(String operacao, RuntimeException erro) {
        pausadoAte = System.nanoTime() + pausaNanos;
        pausado = true;
        metricas.counter("prospero.cotacao.redis.falhas", "operacao", operacao).increment();
        LOG.debugf("Redis de cotação indisponível na %s (%s); PostgreSQL responde por %d ms.", operacao,
                erro.getClass().getSimpleName(), pausaNanos / 1_000_000);
    }
}
