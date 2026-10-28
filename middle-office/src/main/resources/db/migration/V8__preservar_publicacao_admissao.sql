-- RF10c1/ADR-013: expansão aditiva; aplicar com processamento pausado.
-- O recibo conserva evidência presente, sem inventar ACK de linhas já expurgadas.
-- Recuperação: conservar a tabela ao voltar imagem; corrigir por nova migração ou
-- restauração consistente. Não apagar reservas, recibos, deduplicação ou logs.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM outbox o JOIN reserva r ON r.admissao = o.id
        WHERE o.topico IS DISTINCT FROM 'matching.comandos.v1'
           OR o.tipo IS DISTINCT FROM 'ORDEM_ADMITIDA'
           OR o.chave IS DISTINCT FROM r.ticker
           OR octet_length(o.payload) NOT BETWEEN 1 AND 16384
           OR (o.traceparent IS NOT NULL AND (
               o.traceparent !~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'
               OR substring(o.traceparent FROM 4 FOR 32) = repeat('0', 32)
               OR substring(o.traceparent FROM 37 FOR 16) = repeat('0', 16)))
    ) THEN
        RAISE EXCEPTION 'Admissao vinculada incompativel; migracao recusada sem presumir publicacao';
    END IF;
END
$$;

CREATE TABLE admissao_publicacao (
    admissao      UUID         PRIMARY KEY REFERENCES reserva (admissao),
    topico        VARCHAR(64)  NOT NULL CHECK (topico = 'matching.comandos.v1'),
    tipo          VARCHAR(32)  NOT NULL CHECK (tipo = 'ORDEM_ADMITIDA'),
    chave         VARCHAR(8)   NOT NULL REFERENCES ativo_risco (ticker),
    payload       TEXT         NOT NULL CHECK (octet_length(payload) BETWEEN 1 AND 16384),
    traceparent   VARCHAR(55),
    criada_em     TIMESTAMPTZ  NOT NULL,
    confirmada_em TIMESTAMPTZ,
    CHECK (traceparent IS NULL OR (
        traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'
        AND substring(traceparent FROM 4 FOR 32) <> repeat('0', 32)
        AND substring(traceparent FROM 37 FOR 16) <> repeat('0', 16)))
);

-- Backfill pontual da outbox retida: UUIDs indexados e identidade única da admissão.
-- Não filtrar vínculos incompatíveis silenciosamente; a guarda acima recusa-os.
INSERT INTO admissao_publicacao (admissao, topico, tipo, chave, payload, traceparent, criada_em, confirmada_em)
SELECT o.id, o.topico, o.tipo, o.chave, o.payload, o.traceparent, o.criado_em, o.publicado_em
FROM outbox o JOIN reserva r ON r.admissao = o.id;
