-- RF08c (ADR-016): expectativa de crédito D+2 gravada no consumo do negócio e crédito por liquidacao.eventos.v1.
-- Aditiva: negócios anteriores não ganham expectativa (sem backfill). Recuperação: interromper consumidores e
-- restaurar cópia consistente ou aplicar nova migração corretiva; nunca apagar expectativas nem deduplicação.

-- Uma expectativa por (negócio, lado): comprador espera custódia (quantidade), vendedor espera caixa (preço × quantidade).
-- Sem data: a data de liquidação é do BO. id é o identificador derivado do evento esperado (EnvelopeLiquidacao.id).
CREATE TABLE liquidacao_expectativa (
    negocio      VARCHAR(80)    NOT NULL,
    lado         VARCHAR(9)     NOT NULL CHECK (lado IN ('COMPRADOR', 'VENDEDOR')),
    id           UUID           NOT NULL UNIQUE,
    evento       UUID           NOT NULL,
    conta        VARCHAR(16)    NOT NULL REFERENCES conta_risco (conta),
    recurso      VARCHAR(8)     NOT NULL CHECK (recurso IN ('CAIXA', 'CUSTODIA')),
    ticker       VARCHAR(8)     NOT NULL REFERENCES ativo_risco (ticker),
    valor        NUMERIC(18, 2) CHECK (valor > 0),
    quantidade   BIGINT         CHECK (quantidade > 0),
    criada_em    TIMESTAMPTZ    NOT NULL,
    creditada_em TIMESTAMPTZ,
    PRIMARY KEY (negocio, lado),
    CHECK ((lado = 'COMPRADOR') = (recurso = 'CUSTODIA')),
    CHECK ((recurso = 'CAIXA') = (valor IS NOT NULL) AND (recurso = 'CUSTODIA') = (quantidade IS NOT NULL))
);

-- Preparação local recusa conta com crédito pendente; índice parcial acompanha só as pendentes.
CREATE INDEX liquidacao_expectativa_pendente ON liquidacao_expectativa (conta) WHERE creditada_em IS NULL;

-- Deduplicação: só o crédito aplicado ocupa (id, assinatura), na mesma transação do crédito.
CREATE TABLE liquidacao_aplicada (
    id         UUID        PRIMARY KEY,
    assinatura VARCHAR(64) NOT NULL
);

-- Liquidação recebida antes do negócio: guardada por (id, assinatura), sem crédito, até a expectativa existir.
CREATE TABLE liquidacao_aguardando (
    id           UUID           NOT NULL,
    assinatura   VARCHAR(64)    NOT NULL,
    negocio      VARCHAR(80)    NOT NULL,
    lado         VARCHAR(9)     NOT NULL CHECK (lado IN ('COMPRADOR', 'VENDEDOR')),
    conta        VARCHAR(16)    NOT NULL,
    recurso      VARCHAR(8)     NOT NULL CHECK (recurso IN ('CAIXA', 'CUSTODIA')),
    ticker       VARCHAR(8)     NOT NULL,
    valor        NUMERIC(18, 2) CHECK (valor > 0),
    quantidade   BIGINT         CHECK (quantidade > 0),
    particao     INTEGER        NOT NULL,
    deslocamento BIGINT         NOT NULL,
    recebida_em  TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (id, assinatura),
    CHECK ((recurso = 'CAIXA') = (valor IS NOT NULL) AND (recurso = 'CUSTODIA') = (quantidade IS NOT NULL))
);

CREATE INDEX liquidacao_aguardando_recebida ON liquidacao_aguardando (recebida_em);

-- Recusas com motivo enumerado, sem payload, conta ou valor; uma por posição no tópico (reentrega não duplica).
CREATE TABLE liquidacao_recusada (
    id           BIGSERIAL    PRIMARY KEY,
    evento       UUID,
    assinatura   VARCHAR(64),
    motivo       VARCHAR(32)  NOT NULL CHECK (motivo IN ('CONTRATO_INVALIDO', 'ORIGEM_INVALIDA', 'ID_INCOERENTE',
                                                         'CHAVE_INCOERENTE', 'DIVERGENTE', 'REPETICAO_DIVERGENTE',
                                                         'AGUARDANDO_ESGOTADO')),
    particao     INTEGER      NOT NULL,
    deslocamento BIGINT       NOT NULL,
    recusada_em  TIMESTAMPTZ  NOT NULL,
    UNIQUE (particao, deslocamento)
);
