-- RF04 (ADR-012): resultado do risco na ordem, outbox do comando e diagnóstico de mensagens inválidas.
ALTER TABLE ordem DROP CONSTRAINT ordem_estado_check;
ALTER TABLE ordem
    ADD COLUMN motivo_rejeicao VARCHAR(32),
    ADD CONSTRAINT ordem_estado_check CHECK (estado IN ('RECEBIDA', 'RESERVADA', 'REJEITADA')),
    ADD CONSTRAINT ordem_motivo_rejeicao CHECK ((estado = 'REJEITADA') = (motivo_rejeicao IS NOT NULL));

-- Gravada na transação do registro da ordem; publicado_em só após ack do Kafka.
CREATE TABLE outbox (
    id           UUID         PRIMARY KEY,
    topico       VARCHAR(64)  NOT NULL,
    chave        VARCHAR(32)  NOT NULL,
    tipo         VARCHAR(32)  NOT NULL,
    payload      TEXT         NOT NULL,
    traceparent  VARCHAR(55),
    criado_em    TIMESTAMPTZ  NOT NULL,
    publicado_em TIMESTAMPTZ,
    tentativas   INTEGER      NOT NULL DEFAULT 0
);

CREATE INDEX outbox_pendente ON outbox (criado_em) WHERE publicado_em IS NULL;

CREATE TABLE evento_rejeitado (
    id           BIGSERIAL    PRIMARY KEY,
    topico       VARCHAR(64)  NOT NULL,
    particao     INTEGER      NOT NULL,
    deslocamento BIGINT       NOT NULL,
    motivo       VARCHAR(120) NOT NULL,
    recebido_em  TIMESTAMPTZ  NOT NULL
);
