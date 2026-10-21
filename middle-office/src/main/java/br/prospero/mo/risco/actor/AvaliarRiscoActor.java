package br.prospero.mo.risco.actor;

import br.prospero.mo.mensageria.dominio.MensagemSaida;
import br.prospero.mo.mensageria.dominio.Outbox;
import br.prospero.mo.risco.dominio.ComandoRisco;
import br.prospero.mo.risco.dominio.Decisao;
import br.prospero.mo.risco.dominio.Exigencia;
import br.prospero.mo.risco.dominio.MotivoRejeicao;
import br.prospero.mo.risco.dominio.ParametrosRisco;
import br.prospero.mo.risco.dominio.RepositorioRisco;
import br.prospero.mo.risco.helper.CalculoReserva;
import br.prospero.mo.risco.helper.EnvelopeRisco;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Avalia a intenção e reserva o recurso numa única transação: decisão, reserva, linha de controle e resultado na
 * outbox (ADR-012). A decisão existente para a mesma intenção é devolvida sem reavaliar nem republicar.
 */
@ApplicationScoped
public class AvaliarRiscoActor {
    private static final Logger LOG = Logger.getLogger(AvaliarRiscoActor.class);
    private final RepositorioRisco repositorio;
    private final Outbox outbox;
    private final Clock relogio;
    private final MeterRegistry metricas;

    @Inject
    public AvaliarRiscoActor(RepositorioRisco repositorio, Outbox outbox, Clock relogio, MeterRegistry metricas) {
        this.repositorio = repositorio;
        this.outbox = outbox;
        this.relogio = relogio;
        this.metricas = metricas;
    }

    /** Resultado da avaliação e se é repetição de intenção já decidida. */
    public record Avaliacao(Decisao decisao, boolean repetida) {
    }

    @Transactional
    public Avaliacao avaliar(ComandoRisco comando, String traceparent) {
        repositorio.bloquearConta(comando.conta());
        Optional<Decisao> existente = repositorio.decisao(comando.intencao());
        if (existente.isPresent()) {
            metricas.counter("prospero.risco.reentregas").increment();
            return new Avaliacao(existente.get(), true);
        }
        Instant agora = relogio.instant().truncatedTo(ChronoUnit.MICROS);
        Decisao decisao = decidir(comando, agora);
        repositorio.registrar(decisao);
        if (decisao.resultado() == br.prospero.mo.risco.dominio.ResultadoRisco.RESERVADA && comando.admissivel()) {
            repositorio.registrarAdmissao(comando, decisao);
            UUID admissao = br.prospero.mo.risco.helper.EnvelopeAdmissao.id(comando.intencao());
            outbox.gravar(new MensagemSaida(admissao, "matching.comandos.v1", comando.ticker(), "ORDEM_ADMITIDA",
                    br.prospero.mo.risco.helper.EnvelopeAdmissao.serializar(comando, decisao, traceparent), traceparent, agora));
        }
        UUID evento = UUID.randomUUID();
        outbox.gravar(new MensagemSaida(evento, EnvelopeRisco.TOPICO_RESULTADOS, comando.conta(), "RESULTADO_RISCO",
                EnvelopeRisco.resultado(evento, decisao, agora, traceparent), traceparent, agora));
        metricas.counter("prospero.risco.decisoes", "resultado", decisao.resultado().name(),
                "motivo", decisao.motivo() == null ? "NENHUM" : decisao.motivo().name()).increment();
        LOG.infof("Risco decidido: intencao=%s conta=%s resultado=%s motivo=%s", decisao.intencao(), decisao.conta(),
                decisao.resultado(), decisao.motivo());
        return new Avaliacao(decisao, false);
    }

    private Decisao decidir(ComandoRisco comando, Instant agora) {
        if (!repositorio.contaExiste(comando.conta())) {
            return Decisao.rejeitada(comando, MotivoRejeicao.CONTA_DESCONHECIDA, null, agora);
        }
        Optional<ParametrosRisco> parametros = repositorio.parametros(comando.ticker());
        if (parametros.isEmpty()) {
            return Decisao.rejeitada(comando, MotivoRejeicao.ATIVO_DESCONHECIDO, null, agora);
        }
        int versao = parametros.get().versao();
        Exigencia exigencia = CalculoReserva.exigencia(comando, parametros.get());
        return switch (exigencia) {
            case Exigencia.Recusa recusa -> Decisao.rejeitada(comando, recusa.motivo(), versao, agora);
            case Exigencia.Caixa caixa -> repositorio.reservarCaixa(comando.conta(), caixa.valor())
                    ? Decisao.reservada(comando, caixa, versao, agora)
                    : Decisao.rejeitada(comando, MotivoRejeicao.SALDO_INSUFICIENTE, versao, agora);
            case Exigencia.Custodia custodia -> repositorio.reservarCustodia(comando.conta(), comando.ticker(),
                    custodia.quantidade())
                    ? Decisao.reservada(comando, custodia, versao, agora)
                    : Decisao.rejeitada(comando, MotivoRejeicao.CUSTODIA_INSUFICIENTE, versao, agora);
        };
    }
}
