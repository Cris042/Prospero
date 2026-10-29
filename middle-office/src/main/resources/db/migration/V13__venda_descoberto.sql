-- V02-4 (ADR-021, proposta): venda a descoberto simulada com limite de exposição em valor e garantia em caixa.
-- limite_vendido existia desde a V2 com CHECK = 0 e sem uso; passa a valer R$ 200.000,00 por conta.
-- custodia_risco.total/disponivel/reservado seguem não negativos e descrevem só a parte coberta; a custódia efetiva
-- é total - descoberto_executado e pode ficar negativa até o vendido a descoberto.
-- Recuperação: migração corretiva para frente; limite 0 numa conta desliga a venda a descoberto dela.
ALTER TABLE conta_risco DROP CONSTRAINT conta_risco_limite_vendido_check;
ALTER TABLE conta_risco ALTER COLUMN limite_vendido SET DEFAULT 200000.00;
UPDATE conta_risco SET limite_vendido = 200000.00;
ALTER TABLE conta_risco
    ADD CONSTRAINT conta_risco_limite_vendido_check CHECK (limite_vendido >= 0),
    ADD COLUMN exposicao_vendida NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK (exposicao_vendida >= 0);

ALTER TABLE custodia_risco
    ADD COLUMN descoberto_reservado BIGINT         NOT NULL DEFAULT 0 CHECK (descoberto_reservado >= 0),
    ADD COLUMN descoberto_executado BIGINT         NOT NULL DEFAULT 0 CHECK (descoberto_executado >= 0),
    ADD COLUMN garantia_executada   NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK (garantia_executada >= 0),
    ADD CONSTRAINT custodia_risco_garantia_executada CHECK (descoberto_executado > 0 OR garantia_executada = 0);

-- Parte a descoberto da ordem: fixada na decisão, consumida na execução e liberada no encerramento.
ALTER TABLE decisao_risco
    ADD COLUMN quantidade_descoberta BIGINT         CHECK (quantidade_descoberta > 0),
    ADD COLUMN garantia              NUMERIC(18, 2) CHECK (garantia > 0),
    ADD CONSTRAINT decisao_risco_descoberto CHECK ((quantidade_descoberta IS NULL) = (garantia IS NULL));
ALTER TABLE reserva
    ADD COLUMN descoberta_restante BIGINT         NOT NULL DEFAULT 0 CHECK (descoberta_restante >= 0),
    ADD COLUMN garantia_restante   NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK (garantia_restante >= 0),
    ADD CONSTRAINT reserva_descoberto CHECK ((descoberta_restante = 0) = (garantia_restante = 0)),
    ADD CONSTRAINT reserva_descoberto_venda CHECK (descoberta_restante = 0 OR recurso = 'CUSTODIA');
