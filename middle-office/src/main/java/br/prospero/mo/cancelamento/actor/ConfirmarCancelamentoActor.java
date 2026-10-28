package br.prospero.mo.cancelamento.actor;

import br.prospero.mo.cancelamento.dominio.MotivoRecusa;
import br.prospero.mo.cancelamento.dominio.OperacaoCancelamento;
import br.prospero.mo.cancelamento.dominio.RepositorioCancelamento;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Estado da operação quando o matching aplica os fatos do ME (terminal e decisão), sempre na transação do consumidor
 * de matching, sob o lock da conta. Quem libera a reserva é o repositório de matching; aqui só a operação e o resultado.
 */
@ApplicationScoped
public class ConfirmarCancelamentoActor {
    private final RepositorioCancelamento repositorio;
    private final EmissorCancelamento emissor;
    private final MeterRegistry metricas;

    @Inject
    public ConfirmarCancelamentoActor(RepositorioCancelamento repositorio, EmissorCancelamento emissor, MeterRegistry metricas) {
        this.repositorio = repositorio;
        this.emissor = emissor;
        this.metricas = metricas;
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<OperacaoCancelamento> operacao(UUID ordem) {
        return repositorio.buscar(ordem);
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public OperacaoCancelamento travar(UUID ordem) {
        return repositorio.travar(ordem).orElseThrow(() -> new IllegalStateException("Operação de cancelamento ausente."));
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public void terminalAplicado(OperacaoCancelamento operacao, UUID terminal) {
        repositorio.registrarTerminal(operacao.ordem(), terminal);
    }

    /** Decisão APLICADO depois do terminal: confirma e emite o resultado uma única vez. */
    @Transactional(Transactional.TxType.MANDATORY)
    public void confirmar(OperacaoCancelamento operacao) {
        OperacaoCancelamento confirmada = operacao.confirmada();
        repositorio.concluir(confirmada);
        emissor.resultado(confirmada);
        metricas.counter("prospero.cancelamento.concluidos", "estado", confirmada.estado().name()).increment();
    }

    /** Recusa do ME (sem saldo vivo, alvo divergente ou origem OCO): final, sem tocar na reserva. */
    @Transactional(Transactional.TxType.MANDATORY)
    public void recusar(OperacaoCancelamento operacao, MotivoRecusa motivo) {
        OperacaoCancelamento recusada = operacao.recusada(motivo, motivo.name());
        repositorio.concluir(recusada);
        emissor.resultado(recusada);
        metricas.counter("prospero.cancelamento.concluidos", "estado", recusada.estado().name()).increment();
    }
}
