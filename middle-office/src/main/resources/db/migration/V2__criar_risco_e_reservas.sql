-- RF04 (ADR-012): fatos próprios do MO, sem FK nem banco compartilhado com o FO.
-- Parâmetros espelham o catálogo do FO (V2/V4 do FO); versao identifica o conjunto usado em cada decisão.
CREATE TABLE ativo_risco (
    ticker             VARCHAR(8)     PRIMARY KEY,
    lote               BIGINT         NOT NULL CHECK (lote > 0),
    tick               NUMERIC(12, 2) NOT NULL CHECK (tick > 0),
    preco_referencia   NUMERIC(12, 2) NOT NULL CHECK (preco_referencia > 0),
    tunel_percentual   NUMERIC(5, 2)  NOT NULL CHECK (tunel_percentual > 0 AND tunel_percentual <= 50),
    quantidade_maxima  BIGINT         NOT NULL CHECK (quantidade_maxima > 0),
    versao             INTEGER        NOT NULL CHECK (versao > 0)
);

INSERT INTO ativo_risco (ticker, lote, tick, preco_referencia, tunel_percentual, quantidade_maxima, versao) VALUES
    ('ALFA3', 1, 0.01, 10.00, 10, 100000, 1),
    ('BETA3', 1, 0.01, 20.00, 10, 100000, 1),
    ('GAMA3', 1, 0.01, 30.00, 10, 100000, 1),
    ('DELTA3', 1, 0.01, 40.00, 10, 100000, 1),
    ('OMEGA3', 100, 0.05, 50.00, 10, 100000, 1);

-- Linha de controle por conta: suficiência e mutação no mesmo UPDATE condicional (HLD-003, write skew).
CREATE TABLE conta_risco (
    conta          VARCHAR(16)    PRIMARY KEY,
    total          NUMERIC(18, 2) NOT NULL CHECK (total >= 0),
    disponivel     NUMERIC(18, 2) NOT NULL CHECK (disponivel >= 0),
    reservado      NUMERIC(18, 2) NOT NULL CHECK (reservado >= 0),
    -- Sem short na RF04 (decisão do usuário): limite de exposição vendida existe com valor 0.
    limite_vendido NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK (limite_vendido = 0),
    CHECK (disponivel + reservado = total)
);

CREATE TABLE custodia_risco (
    conta      VARCHAR(16) NOT NULL REFERENCES conta_risco (conta),
    ticker     VARCHAR(8)  NOT NULL REFERENCES ativo_risco (ticker),
    total      BIGINT      NOT NULL CHECK (total >= 0),
    disponivel BIGINT      NOT NULL CHECK (disponivel >= 0),
    reservado  BIGINT      NOT NULL CHECK (reservado >= 0),
    PRIMARY KEY (conta, ticker),
    CHECK (disponivel + reservado = total)
);

-- Carteiras sintéticas iniciais (decisão do usuário em 2026-10-18).
INSERT INTO conta_risco (conta, total, disponivel, reservado) VALUES
    ('CONTA-001', 1000000.00, 1000000.00, 0),
    ('CONTA-002', 1000000.00, 1000000.00, 0),
    ('CONTA-003', 1000000.00, 1000000.00, 0);

INSERT INTO custodia_risco (conta, ticker, total, disponivel, reservado)
SELECT c.conta, a.ticker, 1000, 1000, 0 FROM conta_risco c CROSS JOIN ativo_risco a;

-- Uma decisão por intenção (ordem simples ou par OCO): reentrega nunca cria segunda reserva nem resultado.
CREATE TABLE decisao_risco (
    intencao             UUID           PRIMARY KEY,
    evento               UUID           NOT NULL,
    conta                VARCHAR(16)    NOT NULL,
    ticker               VARCHAR(8)     NOT NULL,
    lado                 VARCHAR(6)     NOT NULL CHECK (lado IN ('COMPRA', 'VENDA')),
    resultado            VARCHAR(10)    NOT NULL CHECK (resultado IN ('RESERVADA', 'REJEITADA')),
    motivo               VARCHAR(32),
    valor_reservado      NUMERIC(18, 2) CHECK (valor_reservado > 0),
    quantidade_reservada BIGINT         CHECK (quantidade_reservada > 0),
    versao_parametros    INTEGER,
    decidida_em          TIMESTAMPTZ    NOT NULL,
    CHECK ((resultado = 'REJEITADA') = (motivo IS NOT NULL)),
    CHECK (resultado = 'REJEITADA' OR (valor_reservado IS NULL) <> (quantidade_reservada IS NULL))
);

CREATE TABLE reserva (
    intencao   UUID           PRIMARY KEY REFERENCES decisao_risco (intencao),
    conta      VARCHAR(16)    NOT NULL REFERENCES conta_risco (conta),
    ticker     VARCHAR(8)     NOT NULL REFERENCES ativo_risco (ticker),
    recurso    VARCHAR(8)     NOT NULL CHECK (recurso IN ('CAIXA', 'CUSTODIA')),
    valor      NUMERIC(18, 2) CHECK (valor > 0),
    quantidade BIGINT         CHECK (quantidade > 0),
    estado     VARCHAR(8)     NOT NULL CHECK (estado IN ('ATIVA')),
    criada_em  TIMESTAMPTZ    NOT NULL,
    CHECK ((recurso = 'CAIXA') = (valor IS NOT NULL) AND (recurso = 'CUSTODIA') = (quantidade IS NOT NULL))
);

CREATE INDEX reserva_conta ON reserva (conta);

-- Outbox: gravada na transação da decisão; publicado_em só após ack do Kafka.
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

-- Diagnóstico limitado de mensagens inválidas: sem payload, apenas identificação e motivo.
CREATE TABLE evento_rejeitado (
    id          BIGSERIAL    PRIMARY KEY,
    topico      VARCHAR(64)  NOT NULL,
    particao    INTEGER      NOT NULL,
    deslocamento BIGINT      NOT NULL,
    motivo      VARCHAR(120) NOT NULL,
    recebido_em TIMESTAMPTZ  NOT NULL
);
