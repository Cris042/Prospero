-- RF05c.3b/ADR-013: expansão para pernas OCO; ordens anteriores permanecem como estão.
-- Pares já decididos sem admissão continuam legados (admissao_matching nula), sem backfill.
-- As duas pernas compartilham a admissão do grupo: a unicidade passa a incluir o papel.
-- Implantar consumidores compatíveis antes da emissão v3, com processamento pausado; recuperar por
-- correção aditiva ou restauração consistente validada, sem downgrade binário após efeitos v3.
DROP INDEX ordem_admissao_matching;
CREATE UNIQUE INDEX ordem_admissao_matching ON ordem (admissao_matching, COALESCE(papel_oco, ''))
    WHERE admissao_matching IS NOT NULL;
ALTER TABLE ordem ADD COLUMN quantidade_reduzida BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ordem DROP CONSTRAINT ordem_progresso;
ALTER TABLE ordem ADD CONSTRAINT ordem_progresso CHECK (quantidade_executada >= 0 AND quantidade_cancelada >= 0
    AND quantidade_reduzida >= 0 AND (quantidade_reduzida = 0 OR papel_oco = 'STOP_LOSS')
    AND quantidade_executada + quantidade_cancelada + quantidade_reduzida <= quantidade);
