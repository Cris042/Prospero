package br.prospero.mo.cancelamento.actor;

import br.prospero.mo.cancelamento.dominio.RepositorioCancelamento;
import br.prospero.mo.mensageria.dominio.PromotorCancelamento;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Collection;
import java.util.UUID;

/** Promove AGUARDANDO_ACK na transação que grava o primeiro ACK: ou a marcação e o comando ao matching, ou nada. */
@ApplicationScoped
public class PromoverCancelamentoActor implements PromotorCancelamento {
    private final RepositorioCancelamento repositorio;
    private final EmissorCancelamento emissor;
    private final MeterRegistry metricas;

    @Inject
    public PromoverCancelamentoActor(RepositorioCancelamento repositorio, EmissorCancelamento emissor, MeterRegistry metricas) {
        this.repositorio = repositorio;
        this.emissor = emissor;
        this.metricas = metricas;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void admissoesConfirmadas(Collection<UUID> admissoes) {
        for (var promovida : repositorio.promoverAguardando(admissoes)) {
            emissor.comando(promovida);
            metricas.counter("prospero.cancelamento.promovidos").increment();
        }
    }
}
