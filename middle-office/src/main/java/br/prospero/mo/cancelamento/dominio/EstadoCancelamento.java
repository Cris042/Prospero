package br.prospero.mo.cancelamento.dominio;

/** Ciclo da operação de cancelamento no MO (ADR-013 RF10c2); só TERMINAL_APLICADO e CONFIRMADO tocam o saldo. */
public enum EstadoCancelamento {
    /** Pedido aceito, mas o primeiro ACK do broker para a admissão ainda não foi gravado. */
    AGUARDANDO_ACK,
    /** Comando ao matching na outbox: o ME já pode ter visto a admissão. */
    COMANDO_EMITIDO,
    /** Terminal do cancelamento manual aplicado e reserva liberada, à espera da decisão do ME. */
    TERMINAL_APLICADO,
    /** Decisão APLICADO recebida depois do terminal; resultado CONFIRMADO emitido. */
    CONFIRMADO,
    /** Recusa final, local ou do ME; nunca reabre. */
    RECUSADO
}
