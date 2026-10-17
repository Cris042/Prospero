-- RF01: ativos fictícios aceitos em 2026-10-17 e projeção de leitura do front office.
CREATE TABLE ativo (
    ticker           VARCHAR(8)     PRIMARY KEY,
    nome             VARCHAR(80)    NOT NULL,
    preco_referencia NUMERIC(12, 2) NOT NULL CHECK (preco_referencia > 0),
    tick             NUMERIC(12, 2) NOT NULL CHECK (tick > 0),
    lote             BIGINT         NOT NULL CHECK (lote > 0),
    ordem            SMALLINT       NOT NULL UNIQUE
);

INSERT INTO ativo (ticker, nome, preco_referencia, tick, lote, ordem) VALUES
    ('ALFA3', 'Alfa Participações', 10.00, 0.01, 1, 1),
    ('BETA3', 'Beta Indústria', 20.00, 0.01, 1, 2),
    ('GAMA3', 'Gama Energia', 30.00, 0.01, 1, 3),
    ('DELTA3', 'Delta Logística', 40.00, 0.01, 1, 4),
    ('OMEGA3', 'Ômega Varejo', 50.00, 0.01, 1, 5);

-- Snapshot integral em uma linha: leitura sempre de uma única revisão, sem combinar versões.
CREATE TABLE snapshot_mercado (
    namespace     UUID        NOT NULL,
    ticker        VARCHAR(8)  NOT NULL REFERENCES ativo (ticker),
    revisao       BIGINT      NOT NULL CHECK (revisao > 0),
    sessao        DATE        NOT NULL,
    preparado_em  TIMESTAMPTZ NOT NULL,
    conteudo      JSONB       NOT NULL,
    PRIMARY KEY (namespace, ticker)
);
