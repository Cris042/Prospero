package br.prospero.fo.ordens.dominio;

/**
 * RECEBIDA aguarda o risco do MO; RESERVADA tem recurso reservado (não implica execução); REJEITADA tem motivo
 * (ADR-012). AGUARDANDO_GATILHO distingue stop admitido pendente; ABERTA permite execução (ADR-013).
 */
public enum EstadoOrdem {
    RECEBIDA, RESERVADA, REJEITADA, AGUARDANDO_GATILHO, ABERTA, PARCIALMENTE_EXECUTADA, EXECUTADA, CANCELADA
}
