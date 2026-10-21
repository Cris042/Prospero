package br.prospero.fo.ordens.dominio;

/**
 * RECEBIDA aguarda o risco do MO; RESERVADA tem recurso reservado (não implica execução); REJEITADA tem motivo
 * (ADR-012). Estados de execução entram na RF05.
 */
public enum EstadoOrdem {
    RECEBIDA, RESERVADA, REJEITADA, ABERTA, PARCIALMENTE_EXECUTADA, EXECUTADA, CANCELADA
}
