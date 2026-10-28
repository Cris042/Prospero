package br.prospero.mo.mensageria.dominio;

import java.util.Collection;
import java.util.UUID;

/**
 * Porta chamada pelo publicador, na transação que grava o primeiro ACK da admissão, para promover cancelamentos que
 * esperavam por ele (ADR-013 RF10c2). A implementação fica fora de mensageria; aqui só existe a fronteira.
 */
public interface PromotorCancelamento {
    /** Sem cancelamento a promover; usada por quem publica sem o contexto de cancelamento. */
    PromotorCancelamento NENHUM = admissoes -> { };

    /** Admissões cujo ACK foi gravado nesta transação; participa dela e nunca abre outra. */
    void admissoesConfirmadas(Collection<UUID> admissoes);
}
