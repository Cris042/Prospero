-- RF05c.2/ADR-013: expansão, sem transformação/admissão de ordens antigas.
-- Implantar consumidores compatíveis antes da emissão v2, com processamento pausado.
-- Recuperar por migração corretiva ou restauração consistente validada; não voltar
-- ao binário anterior após efeitos v2 nem apagar fatos/reservas para contornar erro.
ALTER TABLE ordem DROP CONSTRAINT ordem_estado_check;
ALTER TABLE ordem ADD CONSTRAINT ordem_estado_check CHECK (estado IN (
    'RECEBIDA', 'RESERVADA', 'REJEITADA', 'AGUARDANDO_GATILHO',
    'ABERTA', 'PARCIALMENTE_EXECUTADA', 'EXECUTADA', 'CANCELADA'));
