-- V01 (CONTA-005, conta sintética de medição dedicada): aditiva, sem Flyway Undo. Segue o padrão da CONTA-004 (V9) e
-- inclui a linha de revisão da trajetória, que a V11 semeou só para as contas existentes. Recuperação: nova migração
-- ou restauração de backup anterior a esta versão (criar-migration).
INSERT INTO conta (id, nome) VALUES ('CONTA-005', 'Conta de medição (RNF04)');

INSERT INTO trajetoria_revisao (conta) VALUES ('CONTA-005');
