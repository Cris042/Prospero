package br.prospero.mo.cancelamento.dominio;

/** Conjunto fechado de motivos de recusa do contrato do resultado de cancelamento (ADR-013). */
public enum MotivoRecusa {
    RISCO_REJEITADO,
    ADMISSAO_NAO_COMPROVADA,
    ORIGEM_OCO,
    ALVO_DIVERGENTE,
    SEM_SALDO_VIVO
}
