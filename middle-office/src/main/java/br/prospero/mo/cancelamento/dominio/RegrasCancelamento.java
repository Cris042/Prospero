package br.prospero.mo.cancelamento.dominio;

import br.prospero.mo.risco.dominio.Decisao;
import br.prospero.mo.risco.dominio.ResultadoRisco;

import java.util.Optional;

/** Recusas locais do MO, na ordem do ADR-013: a primeira regra que falha decide o motivo. */
public final class RegrasCancelamento {
    private RegrasCancelamento() {
    }

    public static Optional<MotivoRecusa> motivo(SolicitacaoCancelamento pedido, Decisao decisao, Optional<ReservaAlvo> reserva) {
        if (decisao.resultado() == ResultadoRisco.REJEITADA) {
            return Optional.of(MotivoRecusa.RISCO_REJEITADO);
        }
        if (reserva.isEmpty()) {
            return Optional.of(MotivoRecusa.ADMISSAO_NAO_COMPROVADA);
        }
        ReservaAlvo alvo = reserva.get();
        if (alvo.oco()) {
            return Optional.of(MotivoRecusa.ORIGEM_OCO);
        }
        if (divergente(pedido, decisao, alvo)) {
            return Optional.of(MotivoRecusa.ALVO_DIVERGENTE);
        }
        return alvo.encerrada() ? Optional.of(MotivoRecusa.SEM_SALDO_VIVO) : Optional.empty();
    }

    private static boolean divergente(SolicitacaoCancelamento pedido, Decisao decisao, ReservaAlvo alvo) {
        return !pedido.ordem().equals(alvo.ordem()) || !pedido.conta().equals(alvo.conta())
                || !pedido.ticker().equals(alvo.ticker()) || !pedido.dataSessao().equals(alvo.sessao())
                || !pedido.conta().equals(decisao.conta()) || !pedido.ticker().equals(decisao.ticker());
    }
}
