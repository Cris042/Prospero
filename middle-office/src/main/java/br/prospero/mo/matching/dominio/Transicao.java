package br.prospero.mo.matching.dominio;

/** Efeito local decidido pelo domínio; o repositório só persiste (RF05b/RF05c). */
public enum Transicao {
    /** Fato validado sem mudança de recurso nem de estado. */
    NENHUMA,
    /** Stop acionado: marca o acionamento, sem mover recurso. */
    ACIONAR_STOP,
    /** Stop Loss pendente reduzido pela execução do Take Profit irmão. */
    REDUZIR,
    /** Terminal: encerra a reserva (ou a perna) e libera exatamente o restante. */
    ENCERRAR
}
