package br.prospero.mo.cancelamento.actor;

import br.prospero.mo.cancelamento.dominio.OperacaoCancelamento;
import br.prospero.mo.cancelamento.helper.EnvelopeCancelamento;
import br.prospero.mo.mensageria.dominio.MensagemSaida;
import br.prospero.mo.mensageria.dominio.Outbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Grava na outbox, na transação do chamador, as duas mensagens que o MO emite no cancelamento. */
@ApplicationScoped
public class EmissorCancelamento {
    static final String TOPICO_MATCHING = "matching.comandos.v1";
    static final String TOPICO_RESULTADOS = "risco.resultados.v1";
    private final Outbox outbox;
    private final Clock relogio;

    @Inject
    public EmissorCancelamento(Outbox outbox, Clock relogio) {
        this.outbox = outbox;
        this.relogio = relogio;
    }

    /** CANCELAR_ORDEM, chave do ativo e id derivado da ordem; no máximo um por ordem. */
    public void comando(OperacaoCancelamento operacao) {
        Instant agora = relogio.instant().truncatedTo(ChronoUnit.MICROS);
        outbox.gravar(new MensagemSaida(operacao.comando(), TOPICO_MATCHING, operacao.ticker(), "CANCELAR_ORDEM",
                EnvelopeCancelamento.cancelarOrdem(operacao, agora), operacao.traceparent(), agora));
    }

    /** RESULTADO_CANCELAMENTO, chave da conta e id derivado da ordem; no máximo um por ordem. */
    public void resultado(OperacaoCancelamento operacao) {
        Instant agora = relogio.instant().truncatedTo(ChronoUnit.MICROS);
        outbox.gravar(new MensagemSaida(EnvelopeCancelamento.resultadoId(operacao.ordem()), TOPICO_RESULTADOS, operacao.conta(),
                "RESULTADO_CANCELAMENTO", EnvelopeCancelamento.resultado(operacao, agora), operacao.traceparent(), agora));
    }
}
