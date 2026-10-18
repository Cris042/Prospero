-- RF03 (ADR-011): OMEGA3 com lote 100 e tick 0,05 (decisão do usuário em 2026-10-17) e base do túnel na ordem.
UPDATE ativo SET lote = 100, tick = 0.05 WHERE ticker = 'OMEGA3';

ALTER TABLE ordem
    ADD COLUMN preco_base_tunel NUMERIC(12, 2) CHECK (preco_base_tunel > 0),
    ADD COLUMN origem_base      VARCHAR(10) CHECK (origem_base IN ('ULTIMO', 'REFERENCIA')),
    -- Ordens anteriores à RF03 ficam sem base; Market nunca tem base (proteção no matching, RF05).
    ADD CONSTRAINT ordem_base_completa CHECK ((preco_base_tunel IS NULL) = (origem_base IS NULL)),
    ADD CONSTRAINT ordem_market_sem_base CHECK (tipo <> 'MARKET' OR preco_base_tunel IS NULL);
