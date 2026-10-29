-- V02-3 (ADR-020): limite de posição por conta e ativo, em quantidade (RF04).
-- Padrão por ativo em ativo_risco; exceção opcional por conta na própria linha de controle custodia_risco.
-- compra_aberta = compras reservadas e ainda não executadas + compras executadas ainda não liquidadas (D+2).
-- A posição projetada de uma compra é total + compra_aberta + quantidade, comparada por UPDATE condicional.
-- Recuperação: migração corretiva para frente; recalcular compra_aberta pela mesma consulta do backfill.
ALTER TABLE ativo_risco ADD COLUMN limite_posicao BIGINT NOT NULL DEFAULT 50000 CHECK (limite_posicao > 0);
ALTER TABLE custodia_risco
    ADD COLUMN limite_posicao BIGINT CHECK (limite_posicao > 0),
    ADD COLUMN compra_aberta  BIGINT NOT NULL DEFAULT 0 CHECK (compra_aberta >= 0);

-- Robô formador de mercado (CONTA-004) nasce com 100.000 por ativo: sem exceção, nenhuma compra dele passaria.
UPDATE custodia_risco SET limite_posicao = 200000 WHERE conta = 'CONTA-004';

-- Volumes existentes: compras vivas (quantidade ainda não executada) + compras executadas sem crédito D+2.
UPDATE custodia_risco c SET compra_aberta = a.quantidade
FROM (
    SELECT conta, ticker, sum(quantidade) AS quantidade FROM (
        SELECT conta, ticker, COALESCE(quantidade_restante, 0) AS quantidade FROM reserva
        WHERE recurso = 'CAIXA' AND estado IN ('ATIVA', 'PARCIAL')
        UNION ALL
        SELECT conta, ticker, quantidade FROM liquidacao_expectativa
        WHERE recurso = 'CUSTODIA' AND creditada_em IS NULL
    ) abertas GROUP BY conta, ticker
) a
WHERE c.conta = a.conta AND c.ticker = a.ticker;
