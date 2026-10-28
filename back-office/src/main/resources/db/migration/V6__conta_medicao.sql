-- V01 (CONTA-005, conta sintética de medição dedicada): aditiva, sem alterar linhas existentes e sem Flyway Undo.
-- Os CHECKs de conta nasceram sem nome (PostgreSQL: <tabela>_<coluna>_check); aqui são removidos e recriados com
-- nome explícito e o conjunto ampliado. Flyway executa a migração numa transação: falha reverte tudo.
-- Recuperação: correção por nova migração, ou restauração de backup anterior a esta versão (criar-migration).
ALTER TABLE bo_posicao DROP CONSTRAINT bo_posicao_conta_check;
ALTER TABLE bo_posicao ADD CONSTRAINT bo_posicao_conta_check
    CHECK (conta IN ('CONTA-001','CONTA-002','CONTA-003','CONTA-004','CONTA-005'));

ALTER TABLE bo_negocio_fato DROP CONSTRAINT bo_negocio_fato_conta_compra_check;
ALTER TABLE bo_negocio_fato ADD CONSTRAINT bo_negocio_fato_conta_compra_check
    CHECK (conta_compra IN ('CONTA-001','CONTA-002','CONTA-003','CONTA-004','CONTA-005'));
ALTER TABLE bo_negocio_fato DROP CONSTRAINT bo_negocio_fato_conta_venda_check;
ALTER TABLE bo_negocio_fato ADD CONSTRAINT bo_negocio_fato_conta_venda_check
    CHECK (conta_venda IN ('CONTA-001','CONTA-002','CONTA-003','CONTA-004','CONTA-005'));

ALTER TABLE bo_liquidacao DROP CONSTRAINT bo_liquidacao_conta_check;
ALTER TABLE bo_liquidacao ADD CONSTRAINT bo_liquidacao_conta_check
    CHECK (conta IN ('CONTA-001','CONTA-002','CONTA-003','CONTA-004','CONTA-005'));

ALTER TABLE bo_lancamento DROP CONSTRAINT bo_lancamento_conta_check;
ALTER TABLE bo_lancamento ADD CONSTRAINT bo_lancamento_conta_check
    CHECK (conta IN ('CONTA-001','CONTA-002','CONTA-003','CONTA-004','CONTA-005'));

-- Posições zeradas da CONTA-005 para os ativos já cotados (mesmo padrão de V2 para as demais contas).
INSERT INTO bo_posicao(conta,ticker)
SELECT 'CONTA-005', ticker FROM bo_cotacao;
