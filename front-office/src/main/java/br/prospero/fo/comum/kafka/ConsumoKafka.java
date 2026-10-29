package br.prospero.fo.comum.kafka;

import br.prospero.fo.comum.dominio.MensagemInvalidaException;
import br.prospero.fo.comum.dominio.MensagemQuarentena;
import br.prospero.fo.comum.dominio.ParticaoBloqueadaException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Laço de consumo com commit manual depois do efeito local (ADR-012). Mensagem inválida vai para a quarentena com
 * payload (ADR-019) e o offset avança; falha de processamento reposiciona as partições no primeiro registro não
 * confirmado e repete com espera crescente. Falha transitória ({@link ClassificadorFalha}: banco ou rede fora, timeout)
 * nunca conta tentativa nem leva à quarentena. Depois de {@link #TENTATIVAS_MAXIMAS} falhas não transitórias seguidas
 * no mesmo offset o registro vai para a quarentena, para um defeito determinístico não travar a partição; se nem a
 * quarentena for possível (banco fora), segue repetindo sem avançar.
 * {@link ParticaoBloqueadaException} (tipo reconhecido sem leitor) é a exceção: repete sempre e nunca descarta.
 * Cópia espelhada em middle-office (projetos Maven independentes): corrigir nos dois.
 */
public final class ConsumoKafka implements Runnable {
    static final int TENTATIVAS_MAXIMAS = 10;
    private static final Logger LOG = Logger.getLogger(ConsumoKafka.class);
    private static final long ESPERA_INICIAL_MS = 500;
    private static final long ESPERA_MAXIMA_MS = 30_000;
    private final Supplier<Consumer<String, byte[]>> fabrica;
    private final String topico;
    private final Processador processador;
    private final Rejeicao rejeicao;
    private final MeterRegistry metricas;
    private final AtomicInteger vivo = new AtomicInteger();
    private final Map<TopicPartition, Long> falhaEm = new HashMap<>();
    private final Map<TopicPartition, Integer> falhas = new HashMap<>();
    private int transitorias;
    private volatile boolean ativo = true;
    private volatile Consumer<String, byte[]> consumidor;

    /** Efeito local de um registro, numa transação própria. */
    @FunctionalInterface
    public interface Processador {
        void processar(ConsumerRecord<String, byte[]> registro);
    }

    @FunctionalInterface
    public interface Rejeicao {
        void registrar(MensagemQuarentena mensagem);
    }

    public ConsumoKafka(Supplier<Consumer<String, byte[]>> fabrica, String topico, Processador processador,
                        Rejeicao rejeicao, MeterRegistry metricas) {
        this.fabrica = fabrica;
        this.topico = topico;
        this.processador = processador;
        this.rejeicao = rejeicao;
        this.metricas = metricas;
        metricas.gauge("prospero.consumo.ativo", List.of(io.micrometer.core.instrument.Tag.of("topico", topico)), vivo);
    }

    @Override
    public void run() {
        vivo.set(1);
        try (Consumer<String, byte[]> c = fabrica.get()) {
            consumidor = c;
            c.subscribe(List.of(topico));
            while (ativo) {
                try {
                    ciclo(c);
                } catch (WakeupException parada) {
                    break;
                } catch (RuntimeException falha) {
                    pausar(falha);
                }
            }
        } catch (Throwable fatal) {
            LOG.errorf("Consumidor de %s encerrado por erro fatal: %s", topico, fatal.getClass().getSimpleName());
            throw fatal;
        } finally {
            vivo.set(0);
        }
    }

    public void parar() {
        ativo = false;
        Consumer<String, byte[]> c = consumidor;
        if (c != null) {
            c.wakeup();
        }
    }

    void ciclo(Consumer<String, byte[]> c) {
        ConsumerRecords<String, byte[]> registros = c.poll(Duration.ofMillis(500));
        List<TopicPartition> particoes = new ArrayList<>(registros.partitions());
        Map<TopicPartition, OffsetAndMetadata> confirmados = new HashMap<>();
        for (int i = 0; i < particoes.size(); i++) {
            TopicPartition particao = particoes.get(i);
            for (ConsumerRecord<String, byte[]> registro : registros.records(particao)) {
                try {
                    processarOuRejeitar(particao, registro);
                } catch (RuntimeException falha) {
                    if (!confirmados.isEmpty()) {
                        c.commitSync(confirmados);
                    }
                    c.seek(particao, registro.offset());
                    for (TopicPartition restante : particoes.subList(i + 1, particoes.size())) {
                        c.seek(restante, registros.records(restante).getFirst().offset());
                    }
                    throw falha;
                }
                falhaEm.remove(particao);
                falhas.remove(particao);
                transitorias = 0;
                confirmados.put(particao, new OffsetAndMetadata(registro.offset() + 1));
            }
        }
        if (!confirmados.isEmpty()) {
            c.commitSync(confirmados);
        }
    }

    private void processarOuRejeitar(TopicPartition particao, ConsumerRecord<String, byte[]> registro) {
        try {
            processador.processar(registro);
        } catch (ParticaoBloqueadaException bloqueio) {
            // Nunca descarta nem avança o offset, por mais que repita: a recuperação é implantar o leitor.
            contarFalha(particao, registro.offset());
            metricas.counter("prospero.consumo.bloqueadas", "topico", topico, "motivo", bloqueio.getMessage()).increment();
            LOG.errorf("Partição bloqueada: topico=%s particao=%d offset=%d motivo=%s", registro.topic(),
                    registro.partition(), registro.offset(), bloqueio.getMessage());
            throw bloqueio;
        } catch (MensagemInvalidaException invalida) {
            rejeitar(registro, invalida.getMessage(), 1);
        } catch (RuntimeException falha) {
            if (transitoria(registro, falha)) throw falha;
            int seguidas = contarFalha(particao, registro.offset());
            metricas.counter("prospero.consumo.falhas", "topico", topico).increment();
            LOG.warnf("Falha ao processar: topico=%s particao=%d offset=%d tentativa=%d erro=%s", registro.topic(),
                    registro.partition(), registro.offset(), seguidas, falha.getClass().getSimpleName());
            if (seguidas < TENTATIVAS_MAXIMAS) {
                throw falha;
            }
            rejeitar(registro, "falha persistente: " + falha.getClass().getSimpleName(), seguidas);
        }
    }

    /** Sem contar tentativa: a espera cresce com as transitórias seguidas e o offset não avança. */
    private boolean transitoria(ConsumerRecord<String, byte[]> registro, RuntimeException falha) {
        if (!ClassificadorFalha.transitoria(falha)) return false;
        transitorias++;
        metricas.counter("prospero.consumo.transitorias", "topico", topico).increment();
        LOG.warnf("Falha transitória: topico=%s particao=%d offset=%d seguidas=%d erro=%s", registro.topic(),
                registro.partition(), registro.offset(), transitorias, falha.getClass().getSimpleName());
        return true;
    }

    /** Grava antes do commit do offset; se a gravação falhar, a exceção impede o avanço e o registro volta. */
    private void rejeitar(ConsumerRecord<String, byte[]> registro, String motivo, int tentativas) {
        rejeicao.registrar(quarentena(registro, motivo, tentativas));
        LOG.warnf("Mensagem em quarentena: topico=%s particao=%d offset=%d motivo=%s", registro.topic(),
                registro.partition(), registro.offset(), motivo);
        metricas.counter("prospero.consumo.quarentena", "topico", topico, "motivo", motivo).increment();
    }

    public static MensagemQuarentena quarentena(ConsumerRecord<String, byte[]> registro, String motivo, int tentativas) {
        var cabecalho = registro.headers().lastHeader("traceparent");
        String traceparent = cabecalho == null || cabecalho.value() == null ? null
                : new String(cabecalho.value(), StandardCharsets.UTF_8);
        return new MensagemQuarentena(registro.topic(), registro.partition(), registro.offset(), registro.key(),
                traceparent, registro.value(), motivo, tentativas);
    }

    private int contarFalha(TopicPartition particao, long offset) {
        Long anterior = falhaEm.put(particao, offset);
        int seguidas = anterior != null && anterior == offset ? falhas.getOrDefault(particao, 0) + 1 : 1;
        falhas.put(particao, seguidas);
        return seguidas;
    }

    /** Espera crescente pela maior sequência de falhas em curso, limitada a 30 s. */
    private void pausar(RuntimeException falha) {
        int seguidas = Math.max(transitorias, falhas.values().stream().max(Integer::compare).orElse(1));
        long espera = Math.min(ESPERA_MAXIMA_MS, ESPERA_INICIAL_MS << Math.min(seguidas - 1, 10));
        LOG.debugf("Consumo de %s repetirá em %d ms após %s", topico, espera, falha.getClass().getSimpleName());
        try {
            Thread.sleep(espera);
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            ativo = false;
        }
    }
}
