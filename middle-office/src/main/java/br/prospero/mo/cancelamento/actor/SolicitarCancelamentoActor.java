package br.prospero.mo.cancelamento.actor;

import br.prospero.mo.cancelamento.dominio.EstadoCancelamento;
import br.prospero.mo.cancelamento.dominio.MotivoRecusa;
import br.prospero.mo.cancelamento.dominio.OperacaoCancelamento;
import br.prospero.mo.cancelamento.dominio.RegrasCancelamento;
import br.prospero.mo.cancelamento.dominio.RepositorioCancelamento;
import br.prospero.mo.cancelamento.dominio.SolicitacaoCancelamento;
import br.prospero.mo.cancelamento.helper.EnvelopeCancelamento;
import br.prospero.mo.mensageria.dominio.ParticaoBloqueadaException;
import br.prospero.mo.risco.dominio.Decisao;
import br.prospero.mo.risco.dominio.RepositorioRisco;
import br.prospero.mo.risco.helper.EnvelopeAdmissao;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Optional;

/**
 * Aceita o pedido de cancelamento numa transação: conta, depois recibo (a mesma ordem de AvaliarRisco e do publicador).
 * Sem ACK gravado a operação espera; quem grava o primeiro ACK a promove. Nunca libera saldo: isso é do terminal do ME.
 */
@ApplicationScoped
public class SolicitarCancelamentoActor {
    private final RepositorioRisco riscos;
    private final RepositorioCancelamento repositorio;
    private final EmissorCancelamento emissor;
    private final MeterRegistry metricas;

    @Inject
    public SolicitarCancelamentoActor(RepositorioRisco riscos, RepositorioCancelamento repositorio,
                                      EmissorCancelamento emissor, MeterRegistry metricas) {
        this.riscos = riscos;
        this.repositorio = repositorio;
        this.emissor = emissor;
        this.metricas = metricas;
    }

    @Transactional
    public OperacaoCancelamento solicitar(SolicitacaoCancelamento pedido, String traceparent) {
        riscos.bloquearConta(pedido.conta());
        String assinatura = EnvelopeCancelamento.assinatura(pedido);
        Optional<OperacaoCancelamento> existente = repositorio.buscar(pedido.ordem());
        if (existente.isPresent()) {
            return repetida(existente.get(), assinatura);
        }
        // Falhar fechado: o FO só pede depois de aplicar o risco; sem decisão o MO não pode afirmar nada sobre a ordem.
        Decisao decisao = riscos.decisao(pedido.intencao())
                .orElseThrow(() -> new ParticaoBloqueadaException("CANCELAMENTO_SEM_DECISAO_DE_RISCO"));
        OperacaoCancelamento nova = OperacaoCancelamento.nova(pedido, EnvelopeAdmissao.id(pedido.intencao()),
                EnvelopeCancelamento.comando(pedido.ordem()), assinatura, traceparent);
        Optional<MotivoRecusa> recusa = RegrasCancelamento.motivo(pedido, decisao, repositorio.reservaComprovada(pedido.intencao()));
        OperacaoCancelamento operacao = recusa.isPresent() ? recusar(nova, recusa.get()) : admitir(nova);
        metricas.counter("prospero.cancelamento.solicitacoes", "estado", operacao.estado().name()).increment();
        return operacao;
    }

    private OperacaoCancelamento repetida(OperacaoCancelamento existente, String assinatura) {
        if (!existente.assinatura().equals(assinatura)) {
            throw new ParticaoBloqueadaException("CANCELAMENTO_INCONSISTENTE");
        }
        metricas.counter("prospero.cancelamento.reentregas").increment();
        return existente;
    }

    private OperacaoCancelamento recusar(OperacaoCancelamento nova, MotivoRecusa motivo) {
        OperacaoCancelamento recusada = nova.recusada(motivo, null);
        repositorio.inserir(recusada);
        emissor.resultado(recusada);
        return recusada;
    }

    private OperacaoCancelamento admitir(OperacaoCancelamento nova) {
        // O lock do recibo serializa esta decisão com o UPDATE do primeiro ACK: um dos dois enxerga o outro.
        boolean ackGravado = repositorio.travarReciboConfirmado(nova.admissao());
        if (!ackGravado) {
            repositorio.inserir(nova);
            return nova;
        }
        OperacaoCancelamento emitida = nova.comEstado(EstadoCancelamento.COMANDO_EMITIDO);
        repositorio.inserir(emitida);
        emissor.comando(emitida);
        return emitida;
    }
}
