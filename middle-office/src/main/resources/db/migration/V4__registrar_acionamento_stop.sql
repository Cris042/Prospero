-- RF05c.2/ADR-013: expansão preserva reservas, decisões e deduplicação existentes.
-- Não promover stops legados nem fabricar admissão/gatilho para decisões anteriores.
-- Aplicar com processamento pausado e consumidores compatíveis antes da emissão v2.
-- Recuperação por correção aditiva ou restauração consistente validada; não fazer
-- downgrade binário após efeitos v2 nem remover fatos para reprocessar consumo.
ALTER TABLE reserva ADD COLUMN preco_gatilho NUMERIC(12,2),
                    ADD COLUMN stop_acionado BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE reserva ADD CONSTRAINT reserva_gatilho_positivo
    CHECK (preco_gatilho IS NULL OR preco_gatilho > 0 AND preco_gatilho <= 1000000);
ALTER TABLE reserva ADD CONSTRAINT reserva_stop_acionado
    CHECK (NOT stop_acionado OR tipo IN ('STOP', 'STOP_LIMIT') AND preco_gatilho IS NOT NULL);
