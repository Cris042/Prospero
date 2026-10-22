package br.prospero.fo.cotacao.kafka;

import br.prospero.fo.comum.dominio.MensagemInvalidaException;
import br.prospero.fo.comum.kafka.ConfiguracaoKafka;
import br.prospero.fo.cotacao.actor.AplicarCotacaoActor;
import br.prospero.fo.cotacao.dominio.SituacaoCotacao;
import br.prospero.fo.ordens.dominio.MatchingIncoerenteException;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Cotação possui grupo próprio; falha não altera a prontidão financeira das ordens. */
@ApplicationScoped
public class ConsumidorCotacao implements SituacaoCotacao {
    private static final Logger LOG = Logger.getLogger(ConsumidorCotacao.class);
    private final AplicarCotacaoActor actor;
    private final ConfiguracaoKafka configuracao;
    private final MeterRegistry metricas;
    private final boolean habilitada;
    private volatile boolean ativo;
    private volatile KafkaConsumer<String, byte[]> consumidor;
    private volatile CicloCotacao ciclo;
    private Thread linha;

    @Inject
    public ConsumidorCotacao(AplicarCotacaoActor actor, ConfiguracaoKafka configuracao, MeterRegistry metricas,
                              @ConfigProperty(name = "prospero.mensageria.habilitada", defaultValue = "true") boolean habilitada) {
        this.actor = actor;
        this.configuracao = configuracao;
        this.metricas = metricas;
        this.habilitada = habilitada;
        metricas.gauge("prospero.cotacao.em_dia", this, c -> c.emDia() ? 1 : 0);
    }

    void iniciar(@Observes StartupEvent evento) {
        if (habilitada) {
            ativo = true;
            linha = Thread.ofPlatform().name("front-office-cotacao").daemon().start(this::consumir);
        }
    }

    void parar(@Observes ShutdownEvent evento) throws InterruptedException {
        ativo = false;
        if (ciclo != null) ciclo.invalidar();
        if (consumidor != null) consumidor.wakeup();
        if (linha != null) linha.join(5000);
    }

    @Override
    public boolean emDia() {
        return ativo && ciclo != null && ciclo.emDia();
    }

    private void consumir() {
        var propriedades = configuracao.consumidor("front-office-cotacao");
        propriedades.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
        try (var c = new KafkaConsumer<String, byte[]>(propriedades)) {
            consumidor = c;
            ciclo = new CicloCotacao(c, actor::aplicar);
            ciclo.iniciar();
            while (ativo) {
                try {
                    ciclo.rodada();
                } catch (MensagemInvalidaException | MatchingIncoerenteException erro) {
                    ciclo.invalidar();
                    metricas.counter("prospero.cotacao.quarentena").increment();
                    LOG.errorf("Cotação em quarentena; grupo=front-office-cotacao particao=%d offset=%d motivo=%s",
                            ciclo.particaoFalha(), ciclo.offsetFalha(), erro.getMessage());
                    return;
                } catch (RuntimeException erro) {
                    ciclo.invalidar();
                    metricas.counter("prospero.cotacao.falhas").increment();
                    if (erro instanceof WakeupException) throw erro;
                    LOG.warnf("Cotação repetirá após falha transitória: %s", erro.getClass().getSimpleName());
                    Thread.sleep(500);
                }
            }
        } catch (WakeupException parada) {
            LOG.debug("Consumo de cotação encerrado.");
        } catch (InterruptedException parada) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException erro) {
            LOG.errorf("Consumo de cotação interrompido: %s", erro.getClass().getSimpleName());
        } finally {
            if (ciclo != null) ciclo.invalidar();
            consumidor = null;
        }
    }
}
