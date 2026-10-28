-- V01 (CONTA-005, conta sintética de medição dedicada): aditiva, sem Flyway Undo. A carteira nasce zerada; a medição
-- define saldo e custódia por preparação antes de cada execução (CarteiraActor.PREPARAVEIS). Recuperação: nova migração
-- ou restauração de backup anterior a esta versão (criar-migration).
INSERT INTO conta_risco (conta, total, disponivel, reservado) VALUES ('CONTA-005', 0, 0, 0);

INSERT INTO custodia_risco (conta, ticker, total, disponivel, reservado)
SELECT 'CONTA-005', ticker, 0, 0, 0 FROM ativo_risco;
