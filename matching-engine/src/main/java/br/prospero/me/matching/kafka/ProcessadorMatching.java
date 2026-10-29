package br.prospero.me.matching.kafka;

import br.prospero.me.matching.helper.EnvelopeMatching;
import br.prospero.me.matching.helper.SessaoDivergenteException;
import br.prospero.me.matching.helper.SessaoMatching;
import br.prospero.me.matching.jpa.CheckpointMatching;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Laboratório com um escritor serial e assign manual das três partições, protegido por advisory PostgreSQL.
 * Não existe rebalance/escala horizontal. Após falha, reiniciar esta instância: nunca reaplicar sobre motor mutado.
 */
@ApplicationScoped
@Readiness
public class ProcessadorMatching implements HealthCheck {
    private static final Logger LOG = Logger.getLogger(ProcessadorMatching.class);
    private static final Duration ESPERA = Duration.ofSeconds(5);
    private static final List<TopicPartition> PARTICOES = particoes(EnvelopeMatching.COMANDOS);
    private static final List<TopicPartition> TODAS = todasParticoes();
    @Inject io.opentelemetry.api.OpenTelemetry telemetry;
    @Inject io.opentelemetry.api.trace.Tracer tracer;
    private final Instance<AgroalDataSource> fonte;
    private final String servidores;
    private final boolean habilitado;
    private final Counter comandos;
    private final Counter eventos;
    private final Counter falhas;
    private final io.micrometer.core.instrument.Timer nucleo;
    private volatile boolean parar;
    private volatile String estado = "inicializando";
    private volatile String motivo = "";
    private volatile KafkaConsumer<String, byte[]> consumidor;
    private Thread escritor;

    @Inject
    public ProcessadorMatching(Instance<AgroalDataSource> fonte, MeterRegistry metricas,
                               @ConfigProperty(name = "kafka.bootstrap.servers") String servidores,
                               @ConfigProperty(name = "prospero.matching.habilitado", defaultValue = "true") boolean habilitado) {
        this.fonte = fonte; this.servidores = servidores; this.habilitado = habilitado;
        comandos = metricas.counter("prospero.matching.comandos");
        eventos = metricas.counter("prospero.matching.eventos");
        falhas = metricas.counter("prospero.matching.falhas");
        // RNF04 (RF05d): núcleo + serialização do comando, sem poll nem publicação; p99 é o critério de < 1 ms.
        nucleo = io.micrometer.core.instrument.Timer.builder("prospero.matching.comando")
                .description("Processamento de um comando no motor, sem I/O")
                .publishPercentileHistogram().minimumExpectedValue(java.time.Duration.ofNanos(1_000))
                .maximumExpectedValue(java.time.Duration.ofSeconds(1))
                .serviceLevelObjectives(java.time.Duration.ofMillis(1)).register(metricas);
        metricas.gauge("prospero.matching.pronto", this, p -> p.estado.equals("pronto") ? 1 : 0);
    }

    void iniciar(@Observes StartupEvent inicio) {
        if (!habilitado) { estado = "desabilitado"; return; }
        escritor = Thread.ofPlatform().name("matching-escritor").start(this::executar);
    }

    void encerrar(@Observes ShutdownEvent fim) {
        parar = true; estado = "encerrando";
        KafkaConsumer<String, byte[]> atual = consumidor;
        if (atual != null) atual.wakeup();
        if (escritor != null) {
            try { escritor.join(30_000); }
            catch (InterruptedException erro) { Thread.currentThread().interrupt(); }
        }
    }

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("matching-replay-posse").status(!habilitado || estado.equals("pronto"))
                .withData("estado", estado).withData("motivo", motivo).build();
    }

    private void executar() {
        try (CheckpointMatching posse = new CheckpointMatching(fonte.get());
             Admin admin = Admin.create(adminConfig());
             KafkaConsumer<String, byte[]> cliente = new KafkaConsumer<>(consumidorConfig());
             KafkaProducer<String, byte[]> produtor = new KafkaProducer<>(produtorConfig())) {
            consumidor = cliente;
            try {
                Map<String, TopicDescription> identidades = descrever(admin);
                validarConfiguracao(admin);
                cliente.assign(PARTICOES);
                Map<Integer, Long> checkpoints = new HashMap<>(posse.inicializar(
                        identidades.get(EnvelopeMatching.COMANDOS).topicId().toString(),
                        identidades.get(EnvelopeMatching.EVENTOS).topicId().toString()));
                Map<TopicPartition, Long> highwater = validarLog(cliente, checkpoints);
                LocalDate sessaoPersistida = posse.sessao();
                Map<Integer, Long> proximos = new HashMap<>();
                for (TopicPartition p : PARTICOES) { cliente.seek(p, 0); proximos.put(p.partition(), 0L); }
                SessaoMatching motor = new SessaoMatching();
                var execucao = new Execucao(posse, admin, identidades, produtor, motor, checkpoints);
                estado = recuperado(proximos, highwater) ? "pronto" : "replay";
                while (!parar) {
                    posse.validarPosse();
                    validarIdentidade(admin, identidades);
                    validarLog(cliente, checkpoints);
                    var lote = cliente.poll(Duration.ofMillis(200));
                    for (TopicPartition p : PARTICOES) {
                        for (var registro : lote.records(p)) {
                            if (parar) return;
                            if (registro.offset() != proximos.get(p.partition()) || registro.offset() >= 10_000)
                                throw new IllegalStateException("Offset fora do orçamento/origem.");
                            sessaoPersistida = processar(registro, execucao, sessaoPersistida);
                            comandos.increment();
                            proximos.put(p.partition(), registro.offset() + 1);
                        }
                    }
                    if (recuperado(proximos, highwater)) estado = "pronto";
                }
            } catch (Exception erro) {
                bloquear(erro);
                throw erro;
            }
        } catch (Exception erro) {
            bloquear(erro);
        } finally {
            consumidor = null;
            if (parar) estado = "encerrado";
        }
    }

    private record Execucao(CheckpointMatching posse, Admin admin, Map<String, TopicDescription> identidades,
                            KafkaProducer<String, byte[]> produtor, SessaoMatching motor, Map<Integer, Long> checkpoints) { }

    private LocalDate processar(org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]> registro,
                                Execucao execucao, LocalDate sessaoAnterior) throws Exception {
        var comando = EnvelopeMatching.comando(registro.value(), registro.key());
        int destino = Utils.toPositive(Utils.murmur2(registro.key().getBytes(StandardCharsets.UTF_8))) % 3;
        if (destino != registro.partition()) throw new IllegalArgumentException("Partição da chave divergente.");
        try {
            return aplicar(registro, comando, execucao, sessaoAnterior);
        } catch (SessaoDivergenteException erro) {
            throw new IllegalStateException(erro.diagnostico(registro.partition(), registro.offset()), erro);
        }
    }

    private LocalDate aplicar(org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]> registro,
                              EnvelopeMatching.Comando comando, Execucao execucao, LocalDate sessaoAnterior) throws Exception {
        return new RastreioMatching(telemetry, tracer).executar(comando.traceparent(), () -> {
            LocalDate sessaoCheckpoint = SessaoMatching.sessaoDoCheckpoint(comando, sessaoAnterior);
            long inicio = System.nanoTime();
            var resultado = execucao.motor().processar(comando);
            nucleo.record(System.nanoTime() - inicio, TimeUnit.NANOSECONDS);
            long checkpoint = execucao.checkpoints().get(registro.partition());
            if (registro.offset() > checkpoint) {
                // Valida posse/conexão e identidades ANTES de qualquer publicação; erro aborta o motor inteiro.
                execucao.posse().validarPosse();
                validarIdentidade(execucao.admin(), execucao.identidades());
                PublicacaoMatching.publicar(resultado, evento -> {
                    execucao.posse().validarPosse();
                    var saida = new ProducerRecord<String, byte[]>(EnvelopeMatching.EVENTOS, registro.key(), evento);
                    if (RastreioMatching.atual() != null) {
                        saida.headers().add("traceparent", RastreioMatching.atual().getBytes(StandardCharsets.UTF_8));
                    }
                    execucao.produtor().send(saida).get(15, TimeUnit.SECONDS);
                    eventos.increment();
                }, () -> {
                    execucao.posse().validarPosse();
                    execucao.posse().avancar(registro.partition(), checkpoint, registro.offset(), sessaoCheckpoint);
                });
                execucao.checkpoints().put(registro.partition(), registro.offset());
                return sessaoCheckpoint;
            }
            return sessaoAnterior;
        });
    }

    private void bloquear(Exception erro) {
        if (!parar && !estado.equals("bloqueado")) {
            estado = "bloqueado"; falhas.increment();
            if (erro.getCause() instanceof SessaoDivergenteException) {
                // Mensagem montada só com datas, partição e offset: nenhum payload chega ao log.
                motivo = erro.getMessage();
                LOG.errorf("Matching bloqueado: %s", motivo);
                return;
            }
            motivo = erro.getClass().getSimpleName();
            LOG.errorf("Matching bloqueado (%s); preservar logs e reiniciar para replay após corrigir a causa.", motivo);
        }
    }

    private Map<TopicPartition, Long> validarLog(KafkaConsumer<String, byte[]> cliente, Map<Integer, Long> checkpoints) {
        Map<TopicPartition, Long> inicios = cliente.beginningOffsets(TODAS, ESPERA);
        for (long inicio : inicios.values()) if (inicio != 0) throw new IllegalStateException("Log perdeu a origem zero.");
        Map<TopicPartition, Long> finais = cliente.endOffsets(PARTICOES, ESPERA);
        for (TopicPartition p : PARTICOES) {
            long fim = finais.get(p);
            if (fim > 10_000 || checkpoints.get(p.partition()) >= fim)
                throw new IllegalStateException("Log truncado ou orçamento excedido.");
        }
        return finais;
    }

    private Map<String, TopicDescription> descrever(Admin admin) throws Exception {
        Map<String, TopicDescription> descricoes = admin.describeTopics(List.of(EnvelopeMatching.COMANDOS, EnvelopeMatching.EVENTOS))
                .allTopicNames().get(5, TimeUnit.SECONDS);
        for (TopicDescription d : descricoes.values()) {
            if (d.partitions().size() != 3 || d.topicId().equals(org.apache.kafka.common.Uuid.ZERO_UUID))
                throw new IllegalStateException("Tópico requer três partições e identidade.");
        }
        return descricoes;
    }

    private void validarIdentidade(Admin admin, Map<String, TopicDescription> esperadas) throws Exception {
        Map<String, TopicDescription> atuais = descrever(admin);
        for (String nome : esperadas.keySet())
            if (!esperadas.get(nome).topicId().equals(atuais.get(nome).topicId())) throw new IllegalStateException("Tópico recriado.");
    }

    private void validarConfiguracao(Admin admin) throws Exception {
        var recursos = List.of(new ConfigResource(ConfigResource.Type.TOPIC, EnvelopeMatching.COMANDOS),
                new ConfigResource(ConfigResource.Type.TOPIC, EnvelopeMatching.EVENTOS));
        Map<ConfigResource, Config> configs = admin.describeConfigs(recursos).all().get(5, TimeUnit.SECONDS);
        for (Config c : configs.values()) {
            if (!"-1".equals(c.get("retention.ms").value()) || !"-1".equals(c.get("retention.bytes").value())
                    || !"delete".equals(c.get("cleanup.policy").value())) throw new IllegalStateException("Retenção integral requerida.");
        }
    }

    private static boolean recuperado(Map<Integer, Long> proximos, Map<TopicPartition, Long> highwater) {
        return PARTICOES.stream().allMatch(p -> proximos.get(p.partition()) >= highwater.get(p));
    }

    Properties adminConfig() {
        Properties p = new Properties(); p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, servidores);
        p.put(AdminClientConfig.CLIENT_ID_CONFIG, "matching-controle");
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "5000"); return p;
    }

    private Properties consumidorConfig() {
        Properties p = new Properties(); p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servidores);
        p.put(ConsumerConfig.CLIENT_ID_CONFIG, "matching-replay"); p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none"); p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "20");
        p.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "5000");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName()); return p;
    }

    Properties produtorConfig() {
        Properties p = new Properties(); p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servidores);
        p.put(ProducerConfig.CLIENT_ID_CONFIG, "matching-eventos"); p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        p.put(ProducerConfig.ACKS_CONFIG, "all"); p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
        // V02-6 (RNF04): kafka-clients 4.x espera 5 ms por padrão para formar lote; aqui cada evento é esperado antes
        // do checkpoint, então o lote só atrasa. FO e MO já usam 0.
        p.put(ProducerConfig.LINGER_MS_CONFIG, "0");
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000"); p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "10000");
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName()); return p;
    }

    private static List<TopicPartition> particoes(String topico) {
        return List.of(new TopicPartition(topico, 0), new TopicPartition(topico, 1), new TopicPartition(topico, 2));
    }

    private static List<TopicPartition> todasParticoes() {
        List<TopicPartition> p = new ArrayList<>(PARTICOES); p.addAll(particoes(EnvelopeMatching.EVENTOS)); return List.copyOf(p);
    }
}
