-- RF09/ADR-017: expansão aditiva, sem reconstruir fatos de ordens anteriores.
-- Retorno de imagem preserva tabelas. Correção usa nova migração; restore exige backup compatível.
CREATE TABLE trajetoria_revisao (
    conta VARCHAR(16) PRIMARY KEY REFERENCES conta(id),
    revisao BIGINT NOT NULL DEFAULT 0 CHECK (revisao >= 0)
);
INSERT INTO trajetoria_revisao(conta) SELECT id FROM conta;
CREATE TABLE trajetoria_identidade (
    origem VARCHAR(24) NOT NULL,
    evento UUID NOT NULL,
    assinatura CHAR(64) NOT NULL,
    PRIMARY KEY(origem, evento)
);
CREATE TABLE trajetoria_marco (
    conta VARCHAR(16) NOT NULL REFERENCES conta(id),
    sequencia BIGINT NOT NULL CHECK (sequencia > 0),
    ordem UUID NOT NULL REFERENCES ordem(id),
    origem VARCHAR(24) NOT NULL,
    tipo VARCHAR(40) NOT NULL,
    evento UUID NOT NULL,
    assinatura CHAR(64) NOT NULL,
    causacao UUID,
    negocio VARCHAR(64),
    ocorrido_em TIMESTAMPTZ,
    observado_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    quantidade VARCHAR(20),
    motivo VARCHAR(40),
    trace_id VARCHAR(32),
    PRIMARY KEY(conta, sequencia),
    UNIQUE(ordem, origem, evento)
);
CREATE INDEX trajetoria_ordem_sequencia ON trajetoria_marco(conta, ordem, sequencia);

CREATE INDEX evento_rejeitado_topico ON evento_rejeitado(topico);
