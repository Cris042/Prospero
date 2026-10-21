-- RF05c.3b/ADR-013: expansão para pares OCO; reservas e decisões anteriores permanecem como estão.
-- Pares já decididos sem admissão continuam legados (ordem nula, oco falso), sem backfill nem republicação.
-- Aplicar com processamento pausado e consumidores compatíveis antes da emissão v3 pelo MO.
-- Recuperação por correção aditiva ou restauração consistente validada; não fazer downgrade
-- binário após efeitos v3 nem remover fatos/reservas para reprocessar consumo.
ALTER TABLE reserva ADD COLUMN oco BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE reserva ADD CONSTRAINT reserva_oco_sem_ordem CHECK (NOT oco OR ordem IS NULL AND tipo IS NULL);

-- A linha reserva continua dona do caixa/custódia do grupo; cada perna guarda só o próprio progresso.
CREATE TABLE reserva_perna (
    ordem         UUID           PRIMARY KEY,
    intencao      UUID           NOT NULL REFERENCES reserva (intencao),
    papel         VARCHAR(11)    NOT NULL CHECK (papel IN ('TAKE_PROFIT', 'STOP_LOSS')),
    tipo          VARCHAR(10)    NOT NULL,
    preco_limite  NUMERIC(12, 2),
    preco_gatilho NUMERIC(12, 2),
    quantidade    BIGINT         NOT NULL CHECK (quantidade > 0),
    executada     BIGINT         NOT NULL DEFAULT 0 CHECK (executada >= 0),
    reduzida      BIGINT         NOT NULL DEFAULT 0 CHECK (reduzida >= 0),
    cancelada     BIGINT         NOT NULL DEFAULT 0 CHECK (cancelada >= 0),
    stop_acionado BOOLEAN        NOT NULL DEFAULT FALSE,
    estado        VARCHAR(9)     NOT NULL DEFAULT 'ATIVA' CHECK (estado IN ('ATIVA', 'PARCIAL', 'ENCERRADA')),
    UNIQUE (intencao, papel),
    CHECK (executada + reduzida + cancelada <= quantidade),
    CHECK (papel = 'TAKE_PROFIT' AND tipo = 'LIMIT' AND preco_limite IS NOT NULL AND preco_gatilho IS NULL
           AND NOT stop_acionado AND reduzida = 0
        OR papel = 'STOP_LOSS' AND tipo IN ('STOP', 'STOP_LIMIT') AND preco_gatilho IS NOT NULL
           AND (tipo = 'STOP_LIMIT') = (preco_limite IS NOT NULL))
);
