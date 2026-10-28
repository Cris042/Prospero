package br.prospero.bo.posicoes.kafka;

import br.prospero.bo.posicoes.actor.AplicarPosicoesActor;
import br.prospero.bo.posicoes.dominio.*;
import br.prospero.bo.posicoes.helper.EnvelopeMatching;
import br.prospero.bo.posicoes.jpa.CheckpointPosicoesJpa;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.WakeupException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class ConsumidorPosicoes implements SituacaoPosicoes {
    private static final Logger LOG = Logger.getLogger(ConsumidorPosicoes.class);
    private final AplicarPosicoesActor actor;
    private final CheckpointPosicoesJpa checkpoint;
    private final MeterRegistry metricas;
    private final String servidores;
    private final boolean habilitado;
    private final long lagMaximo;
    private volatile boolean ativo;
    private volatile KafkaConsumer<String, byte[]> consumidor;
    @Inject io.opentelemetry.api.OpenTelemetry telemetry;
    @Inject io.opentelemetry.api.trace.Tracer tracer;
    private volatile CicloPosicoes ciclo;
    private Thread linha;
    private String topicoId;

    @Inject public ConsumidorPosicoes(AplicarPosicoesActor actor, CheckpointPosicoesJpa checkpoint, MeterRegistry metricas,
            @ConfigProperty(name = "kafka.bootstrap.servers") String servidores,
            @ConfigProperty(name = "prospero.posicoes.habilitadas", defaultValue = "true") boolean habilitado,
            @ConfigProperty(name = "prospero.posicoes.lag-maximo", defaultValue = "50") long lagMaximo) {
        this.lagMaximo = lagMaximo;
        this.actor = actor;
        this.checkpoint = checkpoint;
        this.metricas = metricas;
        this.servidores = servidores;
        this.habilitado = habilitado;
        metricas.gauge("prospero.posicoes.em_dia", this, c -> c.emDia() ? 1 : 0);
        metricas.gauge("prospero.posicoes.lag_registros", this, ConsumidorPosicoes::lag);
    }

    void iniciar(@Observes StartupEvent evento) {
        if (habilitado) {
            ativo = true;
            linha = Thread.ofPlatform().name("back-office-posicoes").daemon().start(this::consumir);
        }
    }

    void parar(@Observes ShutdownEvent evento) throws InterruptedException {
        ativo = false;
        if (ciclo != null) ciclo.invalidar();
        if (consumidor != null) consumidor.wakeup();
        if (linha != null) linha.join(5000);
    }

    @Override public boolean emDia() { return ativo && ciclo != null && ciclo.emDia(); }
    @Override public boolean semLag() { return ativo && ciclo != null && ciclo.semLag(); }

    /** Registros pendentes na última conferência; -1 enquanto a projeção não foi validada. */
    double lag() {
        var atual = ciclo;
        long valor = atual == null ? Long.MAX_VALUE : atual.lag();
        return valor == Long.MAX_VALUE ? -1 : valor;
    }

    /** O Admin recusa timeout de API menor que o de requisição (padrão 30 s); ambos ficam em 5 s. */
    static java.util.Map<String, Object> configuracaoAdmin(String servidores) {
        return java.util.Map.of("bootstrap.servers", servidores, "default.api.timeout.ms", "5000", "request.timeout.ms", "5000");
    }

    private Properties propriedades() {
        Properties p = new Properties();
        p.put("bootstrap.servers", servidores);
        p.put("client.id", "back-office-posicoes");
        p.put("group.id", "back-office-posicoes");
        p.put("enable.auto.commit", "false");
        p.put("auto.offset.reset", "none");
        p.put("max.poll.records", "1");
        p.put("fetch.max.bytes", "1048576");
        p.put("max.partition.fetch.bytes", "1048576");
        p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        p.put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        p.put("default.api.timeout.ms", "5000");
        return p;
    }

    private void consumir() {
        try (Admin admin = Admin.create(configuracaoAdmin(servidores));
             var c = new KafkaConsumer<String, byte[]>(propriedades())) {
            consumidor = c;
            while (ativo) {
                try { rodada(admin, c); }
                catch (MensagemInvalidaException | ProjecaoIncoerenteException | IllegalArgumentException | ArithmeticException erro) {
                    quarentena(erro.getClass().getSimpleName() + ":" + erro.getMessage());
                    return;
                } catch (IllegalStateException erro) {
                    if ("ORCAMENTO_RACIONAL".equals(erro.getMessage())) { quarentena("ORCAMENTO_RACIONAL"); return; }
                    repetir(erro);
                } catch (RuntimeException erro) {
                    if (erro instanceof WakeupException) throw erro;
                    if (incoerenteNaCausa(erro)) { quarentena("LOG_INCOERENTE"); return; }
                    repetir(erro);
                }
            }
        } catch (WakeupException parada) { LOG.debug("Consumo de posições encerrado."); }
        catch (InterruptedException parada) { Thread.currentThread().interrupt(); }
        catch (RuntimeException erro) { LOG.errorf("Consumo de posições interrompido: %s causa=%s", erro.getClass().getSimpleName(),
                erro.getCause() == null ? "-" : erro.getCause().getClass().getSimpleName()); }
        finally { if (ciclo != null) ciclo.invalidar(); consumidor = null; }
    }

    private void rodada(Admin admin, KafkaConsumer<String, byte[]> c) {
        if (ciclo == null) {
            var descricao = descrever(admin);
            var particoes = descricao.partitions().stream()
                    .map(p -> new org.apache.kafka.common.TopicPartition(EnvelopeMatching.TOPICO, p.partition())).toList();
            if (c.beginningOffsets(particoes).values().stream().anyMatch(o -> o != 0)) {
                throw new ProjecaoIncoerenteException("ORIGEM_PERDIDA");
            }
            topicoId = descricao.topicId().toString();
            checkpoint.iniciar(topicoId);
            ciclo = new CicloPosicoes(c, topicoId, checkpoint::proximo, actor::aplicar, new RastreioPosicoes(telemetry, tracer), lagMaximo);
            ciclo.iniciar();
        }
        ciclo.rodada(() -> {
            if (!topicoId.equals(descrever(admin).topicId().toString())) throw new ProjecaoIncoerenteException("TOPICO_RECRIADO");
        });
    }

    private static TopicDescription descrever(Admin admin) {
        try {
            var d = admin.describeTopics(List.of(EnvelopeMatching.TOPICO)).allTopicNames().get(5, TimeUnit.SECONDS)
                    .get(EnvelopeMatching.TOPICO);
            if (d.partitions().size() != 3) throw new ProjecaoIncoerenteException("PARTICOES_DIVERGENTES");
            return d;
        } catch (ProjecaoIncoerenteException erro) { throw erro; }
        catch (Exception erro) { throw new KafkaException("METADADOS_INDISPONIVEIS", erro); }
    }

    private void repetir(RuntimeException erro) throws InterruptedException {
        if (ciclo != null) ciclo.invalidar();
        metricas.counter("prospero.posicoes.falhas").increment();
        LOG.warnf("Posições repetirão após falha transitória: %s", erro.getClass().getSimpleName());
        Thread.sleep(500);
    }

    private static boolean incoerenteNaCausa(Throwable erro) {
        for (int i = 0; erro != null && i < 12; i++, erro = erro.getCause()) {
            if (erro instanceof ProjecaoIncoerenteException || erro instanceof MensagemInvalidaException) return true;
        }
        return false;
    }

    private void quarentena(String motivo) {
        if (ciclo != null) ciclo.invalidar();
        metricas.counter("prospero.posicoes.quarentena").increment();
        LOG.errorf("Posições em quarentena; particao=%d offset=%d motivo=%s",
                ciclo == null ? -1 : ciclo.particaoFalha(), ciclo == null ? -1 : ciclo.offsetFalha(), motivo);
    }
}
