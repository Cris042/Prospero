-- RF05d (ADR-013): carteira sintética grande do robô formador, não preparável (CarteiraActor.PREPARAVEIS).
-- Sem recarga até a RF08: ao esgotar, o robô para com estado ESGOTADO.
INSERT INTO conta_risco (conta, total, disponivel, reservado) VALUES ('CONTA-004', 100000000.00, 100000000.00, 0);

INSERT INTO custodia_risco (conta, ticker, total, disponivel, reservado)
SELECT 'CONTA-004', ticker, 100000, 100000, 0 FROM ativo_risco;
