-- Aditiva: reservas anteriores seguem legadas, sem inventar metadados.
-- Recuperação: interromper consumidores; restaurar backup consistente ou aplicar nova migração corretiva.
-- Não remover dedup nem reprocessar pagamentos com tabelas vazias; V2 permanece imutável.
ALTER TABLE reserva DROP CONSTRAINT reserva_estado_check;
ALTER TABLE reserva ALTER COLUMN estado TYPE VARCHAR(9);
ALTER TABLE reserva ADD CONSTRAINT reserva_estado_check CHECK (estado IN ('ATIVA','PARCIAL','ENCERRADA'));
ALTER TABLE reserva ADD COLUMN ordem UUID UNIQUE;
ALTER TABLE reserva ADD COLUMN admissao UUID UNIQUE;
ALTER TABLE reserva ADD COLUMN tipo VARCHAR(10);
ALTER TABLE reserva ADD COLUMN validade VARCHAR(3);
ALTER TABLE reserva ADD COLUMN data_sessao DATE;
ALTER TABLE reserva ADD COLUMN quantidade_inicial BIGINT CHECK (quantidade_inicial > 0);
ALTER TABLE reserva ADD COLUMN quantidade_restante BIGINT CHECK (quantidade_restante >= 0);
ALTER TABLE reserva ADD COLUMN preco_limite NUMERIC(12,2);
ALTER TABLE reserva ADD COLUMN preco_maximo NUMERIC(12,2);
ALTER TABLE reserva ADD COLUMN valor_restante NUMERIC(18,2) CHECK (valor_restante >= 0);
ALTER TABLE reserva ADD CONSTRAINT reserva_restante_limite CHECK (quantidade_restante <= quantidade_inicial);
CREATE TABLE matching_evento (id UUID PRIMARY KEY, assinatura VARCHAR(64) NOT NULL);
CREATE TABLE matching_negocio (negocio VARCHAR(80) PRIMARY KEY, assinatura VARCHAR(64) NOT NULL);
