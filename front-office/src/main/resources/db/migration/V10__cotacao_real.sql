-- RF06 (ADR-014): cotação real derivada dos eventos do matching, com deduplicação própria do consumidor
-- front-office-cotacao. Aditiva; reconstruível por replay do tópico (apagar estas tabelas e reposicionar o grupo).
CREATE TABLE cotacao_evento (
    id         UUID PRIMARY KEY,
    assinatura CHAR(64) NOT NULL
);

-- Identidade de negócio é independente da identidade do envelope (republicação/replay).
CREATE TABLE cotacao_negocio (
    id VARCHAR(40) PRIMARY KEY,
    assinatura CHAR(64) NOT NULL
);

CREATE TABLE cotacao_sessao (
    ticker             VARCHAR(8)     NOT NULL REFERENCES ativo (ticker),
    sessao             DATE           NOT NULL,
    ultimo_preco       NUMERIC(12, 2) CHECK (ultimo_preco > 0),
    volume_quantidade  BIGINT         NOT NULL DEFAULT 0 CHECK (volume_quantidade >= 0),
    volume_financeiro  NUMERIC(30, 2) NOT NULL DEFAULT 0 CHECK (volume_financeiro >= 0),
    revisao            BIGINT         NOT NULL CHECK (revisao > 0),
    atualizado_em      TIMESTAMPTZ    NOT NULL,
    encerrada          BOOLEAN        NOT NULL DEFAULT FALSE,
    PRIMARY KEY (ticker, sessao)
);

-- Ofertas em repouso no livro do ME, só agregadas na leitura (sem conta nem intenção).
CREATE TABLE cotacao_oferta (
    ordem    UUID           PRIMARY KEY,
    ticker   VARCHAR(8)     NOT NULL REFERENCES ativo (ticker),
    sessao   DATE           NOT NULL,
    lado     VARCHAR(6)     NOT NULL CHECK (lado IN ('COMPRA', 'VENDA')),
    preco    NUMERIC(12, 2) NOT NULL CHECK (preco > 0),
    restante BIGINT         NOT NULL CHECK (restante > 0)
);
CREATE INDEX cotacao_oferta_livro ON cotacao_oferta (ticker, sessao);
