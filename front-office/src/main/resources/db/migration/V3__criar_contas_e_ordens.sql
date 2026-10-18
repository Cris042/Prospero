-- RF02: contas sintéticas fixas (decisão do usuário em 2026-10-17) e registro de ordens.
CREATE TABLE conta (
    id   VARCHAR(16) PRIMARY KEY,
    nome VARCHAR(80) NOT NULL
);

INSERT INTO conta (id, nome) VALUES
    ('CONTA-001', 'Investidor demo (tela)'),
    ('CONTA-002', 'Conta de testes A'),
    ('CONTA-003', 'Conta de testes B');

CREATE TABLE ordem (
    id            UUID           PRIMARY KEY,
    conta         VARCHAR(16)    NOT NULL REFERENCES conta (id),
    ticker        VARCHAR(8)     NOT NULL REFERENCES ativo (ticker),
    lado          VARCHAR(6)     NOT NULL CHECK (lado IN ('COMPRA', 'VENDA')),
    tipo          VARCHAR(10)    NOT NULL CHECK (tipo IN ('MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT')),
    validade      VARCHAR(3)     NOT NULL CHECK (validade IN ('DIA', 'IOC', 'FOK')),
    quantidade    BIGINT         NOT NULL CHECK (quantidade > 0 AND quantidade <= 1000000),
    preco_limite  NUMERIC(12, 2) CHECK (preco_limite > 0),
    preco_gatilho NUMERIC(12, 2) CHECK (preco_gatilho > 0),
    grupo_oco     UUID,
    papel_oco     VARCHAR(11)    CHECK (papel_oco IN ('TAKE_PROFIT', 'STOP_LOSS')),
    grupo_envio   UUID           NOT NULL,
    estado        VARCHAR(16)    NOT NULL CHECK (estado IN ('RECEBIDA')),
    data_sessao   DATE           NOT NULL,
    recebida_em   TIMESTAMPTZ    NOT NULL,
    versao        BIGINT         NOT NULL DEFAULT 0,
    -- Matriz da RF02 também garantida no banco.
    CHECK ((tipo = 'MARKET' AND validade IN ('IOC', 'FOK') AND preco_limite IS NULL AND preco_gatilho IS NULL)
        OR (tipo = 'LIMIT' AND preco_limite IS NOT NULL AND preco_gatilho IS NULL)
        OR (tipo = 'STOP' AND validade = 'DIA' AND preco_limite IS NULL AND preco_gatilho IS NOT NULL)
        OR (tipo = 'STOP_LIMIT' AND validade = 'DIA' AND preco_limite IS NOT NULL AND preco_gatilho IS NOT NULL)),
    CHECK ((grupo_oco IS NULL) = (papel_oco IS NULL))
);

CREATE INDEX ordem_conta_recebida ON ordem (conta, recebida_em DESC, id DESC);
CREATE INDEX ordem_conta_envio ON ordem (conta, grupo_envio);
CREATE UNIQUE INDEX ordem_oco_papel ON ordem (grupo_oco, papel_oco) WHERE grupo_oco IS NOT NULL;

-- Idempotência por (conta, chave), válida por 24 h; expurgo não remove ordens.
CREATE TABLE idempotencia_envio (
    conta      VARCHAR(16) NOT NULL REFERENCES conta (id),
    chave      VARCHAR(64) NOT NULL,
    hash       CHAR(64)    NOT NULL,
    referencia UUID        NOT NULL,
    criado_em  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (conta, chave)
);

CREATE INDEX idempotencia_envio_criado ON idempotencia_envio (criado_em);

-- Teto de 1000 ordens por conta e sessão, aplicado atomicamente por upsert condicional.
CREATE TABLE limite_ordens (
    conta       VARCHAR(16) NOT NULL REFERENCES conta (id),
    data_sessao DATE        NOT NULL,
    quantidade  INTEGER     NOT NULL CHECK (quantidade BETWEEN 0 AND 1000),
    PRIMARY KEY (conta, data_sessao)
);
