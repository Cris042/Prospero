-- RF05b/ADR-013. Expansão preserva ordens e outbox anteriores; não inventa execução legada.
-- Recuperação: migração corretiva aditiva; após eventos novos, não voltar ao binário antigo.
-- Restauração só de cópia privada validada, sem apagar reservas ou fatos para contornar falhas.
ALTER TABLE ordem DROP CONSTRAINT ordem_estado_check;
ALTER TABLE ordem ALTER COLUMN estado TYPE VARCHAR(24);
ALTER TABLE ordem ADD COLUMN quantidade_executada BIGINT NOT NULL DEFAULT 0,
                  ADD COLUMN admissao_matching UUID,
                  ADD COLUMN quantidade_cancelada BIGINT NOT NULL DEFAULT 0,
                  ADD COLUMN motivo_encerramento VARCHAR(32),
                  ADD CONSTRAINT ordem_progresso CHECK (quantidade_executada >= 0 AND quantidade_cancelada >= 0
                      AND quantidade_executada + quantidade_cancelada <= quantidade),
                  ADD CONSTRAINT ordem_estado_check CHECK (estado IN ('RECEBIDA', 'RESERVADA', 'REJEITADA',
                      'ABERTA', 'PARCIALMENTE_EXECUTADA', 'EXECUTADA', 'CANCELADA'));
CREATE UNIQUE INDEX ordem_admissao_matching ON ordem (admissao_matching) WHERE admissao_matching IS NOT NULL;

-- Fatos de deduplicação duram enquanto o log admitir replay; não são diagnósticos descartáveis.
CREATE TABLE matching_evento (
    id UUID PRIMARY KEY,
    assinatura CHAR(64) NOT NULL
);
CREATE TABLE matching_negocio (
    id VARCHAR(64) PRIMARY KEY,
    assinatura CHAR(64) NOT NULL
);
