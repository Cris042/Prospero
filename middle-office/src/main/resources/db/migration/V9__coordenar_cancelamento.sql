-- RF10c2b/ADR-013: coordenação do cancelamento manual no MO; expansão aditiva, sem tocar V1-V8.
-- Uma linha por ordem (PK): repetição devolve a mesma operação e a recusa é final. Nenhuma coluna é preenchida por
-- tempo ou HTTP; o saldo só é liberado pelo terminal do ME aplicado em matching.
-- Recuperação: ao voltar imagem, conservar a tabela (ela não é lida por versões anteriores); corrigir por nova migração
-- ou restauração consistente. Não apagar operações, reservas, recibos nem deduplicação.
CREATE TABLE cancelamento_operacao (
    ordem       UUID         PRIMARY KEY,
    -- A decisão existe também para ordens rejeitadas (sem reserva); por isso a chave estrangeira aponta para ela.
    intencao    UUID         NOT NULL REFERENCES decisao_risco (intencao),
    -- Admissão alvo derivada da intenção; sem chave estrangeira para o recibo porque o legado não tem recibo.
    admissao    UUID         NOT NULL,
    conta       VARCHAR(16)  NOT NULL,
    ticker      VARCHAR(8)   NOT NULL,
    data_sessao DATE         NOT NULL,
    solicitacao UUID         NOT NULL UNIQUE,
    comando     UUID         NOT NULL UNIQUE,
    assinatura  CHAR(64)     NOT NULL CHECK (assinatura ~ '^[0-9a-f]{64}$'),
    estado      VARCHAR(18)  NOT NULL
        CHECK (estado IN ('AGUARDANDO_ACK', 'COMANDO_EMITIDO', 'TERMINAL_APLICADO', 'CONFIRMADO', 'RECUSADO')),
    terminal    UUID,
    decisao     VARCHAR(16)  CHECK (decisao IN ('APLICADO', 'SEM_SALDO_VIVO', 'ALVO_DIVERGENTE', 'ORIGEM_OCO')),
    motivo      VARCHAR(24)  CHECK (motivo IN ('RISCO_REJEITADO', 'ADMISSAO_NAO_COMPROVADA', 'ORIGEM_OCO',
                                               'ALVO_DIVERGENTE', 'SEM_SALDO_VIVO')),
    traceparent VARCHAR(55),
    criada_em   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decidida_em TIMESTAMPTZ,
    CHECK ((estado = 'RECUSADO') = (motivo IS NOT NULL)),
    CHECK ((estado IN ('TERMINAL_APLICADO', 'CONFIRMADO')) = (terminal IS NOT NULL)),
    CHECK ((estado IN ('CONFIRMADO', 'RECUSADO')) = (decidida_em IS NOT NULL)),
    -- Decisão do ME: só CONFIRMADO (APLICADO) ou RECUSADO (uma das três recusas, igual ao motivo); recusa local não tem decisão.
    CHECK (CASE estado
               WHEN 'CONFIRMADO' THEN COALESCE(decisao = 'APLICADO', FALSE)
               WHEN 'RECUSADO' THEN decisao IS NULL OR (decisao <> 'APLICADO' AND decisao = motivo)
               ELSE decisao IS NULL
           END),
    CHECK (traceparent IS NULL OR (
        traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'
        AND substring(traceparent FROM 4 FOR 32) <> repeat('0', 32)
        AND substring(traceparent FROM 37 FOR 16) <> repeat('0', 16)))
);

-- A promoção no primeiro ACK procura só as operações que ainda esperam por ele.
CREATE INDEX cancelamento_operacao_aguardando ON cancelamento_operacao (admissao) WHERE estado = 'AGUARDANDO_ACK';
