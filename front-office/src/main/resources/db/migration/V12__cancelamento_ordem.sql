-- RF10 (ADR-013): operação de cancelamento manual, uma por ordem. Expansão aditiva.
-- Retorno de imagem preserva a tabela e as linhas; correção usa nova migração; restore exige backup compatível.
CREATE TABLE cancelamento_operacao (
    ordem        UUID        PRIMARY KEY REFERENCES ordem(id),
    conta        VARCHAR(16) NOT NULL REFERENCES conta(id),
    estado       VARCHAR(16) NOT NULL,
    decisao      VARCHAR(16),
    motivo       VARCHAR(32),
    solicitada_em TIMESTAMPTZ NOT NULL,
    atualizada_em TIMESTAMPTZ NOT NULL,
    versao       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT cancelamento_estado_check CHECK (estado IN
        ('AGUARDANDO_RISCO', 'SOLICITADO', 'DECIDIDO', 'CONFIRMADO', 'RECUSADO')),
    CONSTRAINT cancelamento_decisao_check CHECK (decisao IS NULL OR
        (decisao IN ('APLICADO', 'SEM_SALDO_VIVO', 'ALVO_DIVERGENTE', 'ORIGEM_OCO')
         AND estado IN ('DECIDIDO', 'CONFIRMADO', 'RECUSADO'))),
    CONSTRAINT cancelamento_motivo_check CHECK ((estado = 'RECUSADO') = (motivo IS NOT NULL)
        AND (motivo IS NULL OR motivo IN ('SEM_SALDO_VIVO', 'ALVO_DIVERGENTE', 'ORIGEM_OCO',
            'ADMISSAO_NAO_COMPROVADA', 'RISCO_REJEITADO')))
);
-- Alimenta o gauge de pendência (D6): só as operações ainda sem resultado final.
CREATE INDEX cancelamento_pendente ON cancelamento_operacao (solicitada_em)
    WHERE estado IN ('AGUARDANDO_RISCO', 'SOLICITADO', 'DECIDIDO');
