package br.prospero.mo.liquidacao.dominio;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta da liquidação no MO (ADR-016). Mutações participam da transação do chamador: crédito, deduplicação,
 * aguardando e recusa confirmam ou revertem juntos. Ordem de locks: contas (ordenadas) e depois o id da liquidação.
 */
public interface RepositorioLiquidacao {
    /** Trava a conta do evento e o id da liquidação; serializa com a transação que grava a expectativa. */
    void bloquear(String conta, UUID id);

    Optional<Expectativa> expectativa(UUID id);

    /** Assinatura do crédito já aplicado com este id. */
    Optional<String> aplicada(UUID id);

    boolean aguardando(UUID id, String assinatura);

    long totalAguardando();

    /** Crédito no total e no disponível da conta, expectativa marcada e deduplicação gravada, ou falha técnica. */
    void creditar(UUID id, String assinatura, CreditoEsperado credito);

    void aguardar(EventoLiquidacao evento, int particao, long deslocamento);

    /** Uma recusa por posição no tópico; reentrega da mesma posição não duplica. */
    void recusar(UUID id, String assinatura, MotivoRecusa motivo, int particao, long deslocamento);

    /**
     * No consumo do negócio, com as contas já travadas: grava as expectativas, aplica o aguardando idêntico e recusa
     * os divergentes do mesmo id ({@link RegrasLiquidacao#resolver}).
     */
    void registrarExpectativas(UUID eventoNegocio, List<CreditoEsperado> esperados);

    ResumoLiquidacao resumo();
}
