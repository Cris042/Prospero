package br.prospero.mo.risco.actor;

import br.prospero.mo.mensageria.dominio.MensagemSaida;
import br.prospero.mo.mensageria.dominio.Outbox;
import br.prospero.mo.risco.dominio.ComandoRisco;
import br.prospero.mo.risco.dominio.Decisao;
import br.prospero.mo.risco.dominio.Exigencia;
import br.prospero.mo.risco.dominio.MotivoRejeicao;
import br.prospero.mo.risco.dominio.ParametrosRisco;
import br.prospero.mo.risco.dominio.RepositorioRisco;
import br.prospero.mo.risco.dominio.ReservaVenda;
import br.prospero.mo.risco.helper.CalculoReserva;
import br.prospero.mo.risco.helper.EnvelopeRisco;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
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
        Decidida decidida = decidir(comando, agora);
        Decisao decisao = decidida.decisao();
        repositorio.registrar(decisao);
        if (decidida.descoberta() > 0) {
            repositorio.registrarDescoberto(decisao.intencao(), decidida.descoberta(), decidida.garantia());
        }
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

    /**
     * Limite de posição antes do caixa (ADR-020), ambos por UPDATE condicional na linha de controle. Sem caixa, a
     * posição reservada é devolvida na mesma transação, então nenhuma rejeição deixa exposição presa.
     */
    private Decisao reservarCompra(ComandoRisco comando, Exigencia.Caixa caixa, int versao, Instant agora) {
        long quantidade = CalculoReserva.quantidade(comando);
        if (!repositorio.reservarPosicaoCompra(comando.conta(), comando.ticker(), quantidade)) {
            return Decisao.rejeitada(comando, MotivoRejeicao.LIMITE_POSICAO_EXCEDIDO, versao, agora);
        }
        if (repositorio.reservarCaixa(comando.conta(), caixa.valor())) {
            return Decisao.reservada(comando, caixa, versao, agora);
        }
        repositorio.liberarPosicaoCompra(comando.conta(), comando.ticker(), quantidade);
        return Decisao.rejeitada(comando, MotivoRejeicao.SALDO_INSUFICIENTE, versao, agora);
    }

    /** Venda coberta, parcialmente ou totalmente a descoberto (ADR-021); a garantia usa o teto da base do túnel. */
    private Decidida reservarVenda(ComandoRisco comando, Exigencia.Custodia custodia, ParametrosRisco parametros,
                                   int versao, Instant agora) {
        BigDecimal teto = comando.precoBase() == null ? null : CalculoReserva.teto(comando.precoBase(), parametros);
        return switch (repositorio.reservarVenda(comando.conta(), comando.ticker(), custodia.quantidade(), teto)) {
            case ReservaVenda.Recusada recusa -> new Decidida(Decisao.rejeitada(comando, recusa.motivo(), versao, agora));
            case ReservaVenda.Reservada reservada -> new Decidida(Decisao.reservada(comando, custodia, versao, agora),
                    reservada.descoberta(), reservada.garantia());
        };
    }

    /** Decisão e, na venda, a parte a descoberto a gravar junto da reserva (ADR-021). */
    private record Decidida(Decisao decisao, long descoberta, BigDecimal garantia) {
        Decidida(Decisao decisao) {
            this(decisao, 0, null);
        }
    }

    private Decidida decidir(ComandoRisco comando, Instant agora) {
        if (!repositorio.contaExiste(comando.conta())) {
            return new Decidida(Decisao.rejeitada(comando, MotivoRejeicao.CONTA_DESCONHECIDA, null, agora));
        }
        Optional<ParametrosRisco> parametros = repositorio.parametros(comando.ticker());
        if (parametros.isEmpty()) {
            return new Decidida(Decisao.rejeitada(comando, MotivoRejeicao.ATIVO_DESCONHECIDO, null, agora));
        }
        int versao = parametros.get().versao();
        Exigencia exigencia = CalculoReserva.exigencia(comando, parametros.get());
        return switch (exigencia) {
            case Exigencia.Recusa recusa -> new Decidida(Decisao.rejeitada(comando, recusa.motivo(), versao, agora));
            case Exigencia.Caixa caixa -> new Decidida(reservarCompra(comando, caixa, versao, agora));
            case Exigencia.Custodia custodia -> reservarVenda(comando, custodia, parametros.get(), versao, agora);
        };
    }
}
