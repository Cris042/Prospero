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
import java.util.function.LongSupplier;

/**
 * Snapshot por ativo no Redis (ADR-022): hash {@code prospero:cotacao:<ticker>} com versão e JSON, gravado por Lua
 * com compare-and-set. Falha abre uma janela de {@code pausa} sem tentar o Redis, para a leitura não pagar o timeout
 * a cada pedido; o PostgreSQL responde nesse intervalo. Como gravações podem ter sido perdidas durante a falha, o
 * cache fica sujo: a primeira operação depois da volta apaga todos os snapshots antes de ler ou gravar, e a leitura
 * os repõe do PostgreSQL.
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
    /** Poucos ativos fixos (cinco no laboratório): KEYS é limitado e roda uma vez por recuperação. */
    static final String INVALIDAR = """
            local chaves = redis.call('KEYS', 'prospero:cotacao:*')
            for _, chave in ipairs(chaves) do redis.call('DEL', chave) end
            return #chaves""";

    /** Comando Redis com resposta em texto ({@code null} quando ausente); exceção é falha de comunicação. */
    @FunctionalInterface
    interface Executor {
        String executar(String comando, String... argumentos);
    }

    private final Executor executor;
    private final boolean habilitado;
    private final long ttlMs;
    private final long pausaNanos;
    private final LongSupplier relogio;
    private final MeterRegistry metricas;
    private volatile boolean pausado;
    private volatile boolean sujo;
    private volatile long pausadoAte;

    @Inject
    public CacheCotacaoRedis(Instance<RedisDataSource> redis, MeterRegistry metricas,
                             @ConfigProperty(name = "prospero.cotacao.redis.habilitado", defaultValue = "false") boolean habilitado,
                             @ConfigProperty(name = "prospero.cotacao.redis.ttl", defaultValue = "PT1H") Duration ttl,
                             @ConfigProperty(name = "prospero.cotacao.redis.pausa", defaultValue = "PT5S") Duration pausa) {
        this((comando, argumentos) -> {
            var resposta = redis.get().execute(comando, argumentos);
            return resposta == null ? null : resposta.toString();
        }, metricas, habilitado, ttl, pausa, System::nanoTime);
    }

    CacheCotacaoRedis(Executor executor, MeterRegistry metricas, boolean habilitado, Duration ttl, Duration pausa,
                      LongSupplier relogio) {
        this.executor = executor;
        this.metricas = metricas;
        this.habilitado = habilitado;
        this.ttlMs = ttl.toMillis();
        this.pausaNanos = pausa.toNanos();
        this.relogio = relogio;
    }

    static String chave(String ticker) { return "prospero:cotacao:" + ticker; }

    @Override
    public Optional<CotacaoEmCache> ler(String ticker) {
        if (!pronto()) return Optional.empty();
        try {
            String texto = executor.executar("HGET", chave(ticker), "d");
            if (texto == null) return Optional.empty();
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
        if (!pronto()) return;
        try {
            // Lua compara com >=: o "ausente" (versão zero) nunca sobrescreve snapshot real nem se repete.
            String resposta = executor.executar("EVAL", GRAVAR, "1", chave(ticker), versao, dados, Long.toString(ttlMs));
            metricas.counter("prospero.cotacao.redis.gravacoes", "resultado",
                    "1".equals(resposta) ? "gravada" : "ignorada").increment();
        } catch (RuntimeException erro) {
            falhou("gravacao", erro);
        }
    }

    /** Fora da pausa e limpo; se ficou sujo por uma falha, invalida tudo primeiro e só então libera. */
    private boolean pronto() {
        if (!habilitado) return false;
        if (pausado && relogio.getAsLong() - pausadoAte < 0) return false;
        pausado = false;
        if (!sujo) return true;
        try {
            executor.executar("EVAL", INVALIDAR, "0");
            sujo = false;
            metricas.counter("prospero.cotacao.redis.invalidacoes").increment();
            return true;
        } catch (RuntimeException erro) {
            falhou("invalidacao", erro);
            return false;
        }
    }

    private void falhou(String operacao, RuntimeException erro) {
        pausadoAte = relogio.getAsLong() + pausaNanos;
        pausado = true;
        sujo = true;
        metricas.counter("prospero.cotacao.redis.falhas", "operacao", operacao).increment();
        LOG.debugf("Redis de cotação indisponível na %s (%s); PostgreSQL responde por %d ms.", operacao,
                erro.getClass().getSimpleName(), pausaNanos / 1_000_000);
    }
}
