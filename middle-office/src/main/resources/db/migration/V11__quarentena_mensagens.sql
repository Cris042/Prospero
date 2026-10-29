-- V02-2 (ADR-019): a quarentena substitui o diagnóstico sem payload. Erro permanente retira a mensagem do fluxo com
-- tudo o que é preciso para reaplicá-la depois de corrigida a causa. Linhas antigas ficam sem payload: só descarte.
-- Retenção: o relay apaga linhas com mais de 30 dias. Acesso: só o usuário do banco do serviço e a API com escopo
-- quarentena:reprocessar, que nunca devolve o payload.
-- Recuperação: migração corretiva para frente; não há volta automática para evento_rejeitado.
ALTER TABLE evento_rejeitado RENAME TO mensagem_quarentena;
ALTER TABLE mensagem_quarentena RENAME COLUMN recebido_em TO quarentenado_em;
ALTER TABLE mensagem_quarentena
    ADD COLUMN chave        VARCHAR(200),
    ADD COLUMN traceparent  VARCHAR(55),
    ADD COLUMN payload      BYTEA CHECK (payload IS NULL OR octet_length(payload) <= 1048576),
    ADD COLUMN tentativas   INTEGER NOT NULL DEFAULT 1 CHECK (tentativas >= 1),
    ADD COLUMN resolucao    VARCHAR(12) CHECK (resolucao IN ('REPROCESSADA', 'DESCARTADA')),
    ADD COLUMN resolvido_em TIMESTAMPTZ,
    ADD CONSTRAINT mensagem_quarentena_resolucao CHECK ((resolucao IS NULL) = (resolvido_em IS NULL));
-- O diagnóstico antigo podia repetir a posição quando o commit do offset se perdia; fica a linha mais antiga.
DELETE FROM mensagem_quarentena a USING mensagem_quarentena b
WHERE a.topico = b.topico AND a.particao = b.particao AND a.deslocamento = b.deslocamento AND a.id > b.id;
CREATE UNIQUE INDEX mensagem_quarentena_posicao ON mensagem_quarentena (topico, particao, deslocamento);
CREATE INDEX mensagem_quarentena_pendente ON mensagem_quarentena (quarentenado_em) WHERE resolvido_em IS NULL;
COMMENT ON TABLE mensagem_quarentena IS 'Mensagens Kafka retiradas por erro permanente, com payload, até reprocessar ou descartar (ADR-019)';
