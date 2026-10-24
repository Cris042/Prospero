-- RF08b (ADR-016): expansão aditiva sobre a V2. Calendário versionado, fato econômico do negócio, razão D0
-- append-only e liquidação PENDENTE por lado. Sem backfill: negócios RF07 sem fato bloqueiam o consumo
-- (RAZAO_SEM_ORIGEM) até recriar bo-dados. Migração aplicada não é editada; correção vem em nova versão.

-- Feriados nacionais fixos (datas fixas em lei) de 2026 e 2027; sem API de edição. Fora de [inicio, fim] o
-- cálculo do D+2 falha fechado com CALENDARIO_INSUFICIENTE.
CREATE TABLE bo_calendario (
    id integer PRIMARY KEY CHECK (id=1),
    inicio date NOT NULL,
    fim date NOT NULL,
    CHECK (inicio<=fim)
);
CREATE TABLE bo_feriado (
    data date PRIMARY KEY,
    nome varchar(60) NOT NULL
);
INSERT INTO bo_calendario(id,inicio,fim) VALUES (1,DATE '2026-01-01',DATE '2027-12-31');
INSERT INTO bo_feriado(data,nome) VALUES
    (DATE '2026-01-01','Confraternização Universal'),
    (DATE '2026-04-21','Tiradentes'),
    (DATE '2026-05-01','Dia do Trabalho'),
    (DATE '2026-09-07','Independência do Brasil'),
    (DATE '2026-10-12','Nossa Senhora Aparecida'),
    (DATE '2026-11-02','Finados'),
    (DATE '2026-11-15','Proclamação da República'),
    (DATE '2026-11-20','Dia Nacional de Zumbi e da Consciência Negra'),
    (DATE '2026-12-25','Natal'),
    (DATE '2027-01-01','Confraternização Universal'),
    (DATE '2027-04-21','Tiradentes'),
    (DATE '2027-05-01','Dia do Trabalho'),
    (DATE '2027-09-07','Independência do Brasil'),
    (DATE '2027-10-12','Nossa Senhora Aparecida'),
    (DATE '2027-11-02','Finados'),
    (DATE '2027-11-15','Proclamação da República'),
    (DATE '2027-11-20','Dia Nacional de Zumbi e da Consciência Negra'),
    (DATE '2027-12-25','Natal');
ALTER TABLE bo_feriado ADD CONSTRAINT bo_feriado_coberto CHECK (data BETWEEN DATE '2026-01-01' AND DATE '2027-12-31');

-- Fato econômico: confirmado na mesma transação do checkpoint RF07. Preço/valor em centavos; quantidade inteira.
CREATE TABLE bo_negocio_fato (
    negocio_id varchar(64) PRIMARY KEY REFERENCES bo_negocio(id),
    ticker varchar(6) NOT NULL REFERENCES bo_cotacao(ticker),
    data_sessao date NOT NULL,
    data_liquidacao date NOT NULL CHECK (data_liquidacao>data_sessao),
    preco numeric(9,2) NOT NULL CHECK (preco>0 AND preco<=1000000.00),
    quantidade bigint NOT NULL CHECK (quantidade BETWEEN 1 AND 1000000),
    valor numeric(15,2) NOT NULL,
    conta_compra varchar(9) NOT NULL CHECK (conta_compra IN ('CONTA-001','CONTA-002','CONTA-003','CONTA-004')),
    ordem_compra uuid NOT NULL,
    conta_venda varchar(9) NOT NULL CHECK (conta_venda IN ('CONTA-001','CONTA-002','CONTA-003','CONTA-004')),
    ordem_venda uuid NOT NULL,
    CHECK (valor=preco*quantidade),
    CHECK (ordem_compra<>ordem_venda)
);

-- Estado operacional por (negócio, lado); PENDENTE → LIQUIDADO pertence à RF08d (UPDATE condicional).
CREATE TABLE bo_liquidacao (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    negocio_id varchar(64) NOT NULL REFERENCES bo_negocio_fato(negocio_id),
    lado varchar(9) NOT NULL CHECK (lado IN ('COMPRADOR','VENDEDOR')),
    conta varchar(9) NOT NULL CHECK (conta IN ('CONTA-001','CONTA-002','CONTA-003','CONTA-004')),
    estado varchar(10) NOT NULL DEFAULT 'PENDENTE' CHECK (estado IN ('PENDENTE','LIQUIDADO')),
    CONSTRAINT bo_liquidacao_unica UNIQUE (negocio_id,lado),
    CONSTRAINT bo_liquidacao_conta_lado UNIQUE (negocio_id,lado,conta)
);
-- Leitura por conta com igualdade parametrizada e ordem decrescente limitada (GET /api/v1/negocios).
CREATE INDEX bo_liquidacao_conta_id ON bo_liquidacao(conta,id);

-- Partidas dobradas sem estado. Unidade BRL usa valor em centavos; ativo usa quantidade inteira.
CREATE TABLE bo_lancamento (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transacao uuid NOT NULL,
    negocio_id varchar(64) NOT NULL,
    lado varchar(9) NOT NULL CHECK (lado IN ('COMPRADOR','VENDEDOR')),
    conta varchar(9) NOT NULL CHECK (conta IN ('CONTA-001','CONTA-002','CONTA-003','CONTA-004')),
    momento varchar(2) NOT NULL CHECK (momento IN ('D0','D2')),
    data_referencia date NOT NULL,
    conta_contabil varchar(18) NOT NULL
        CHECK (conta_contabil IN ('CAIXA','CAIXA_A_RECEBER','CUSTODIA','CUSTODIA_A_RECEBER','CAMARA')),
    natureza char(1) NOT NULL CHECK (natureza IN ('D','C')),
    unidade varchar(6) NOT NULL CHECK (unidade IN ('BRL','ALFA3','BETA3','GAMA3','DELTA3','OMEGA3')),
    valor numeric(15,2) CHECK (valor>0),
    quantidade bigint CHECK (quantidade>0),
    CHECK ((unidade='BRL' AND valor IS NOT NULL AND quantidade IS NULL)
        OR (unidade<>'BRL' AND quantidade IS NOT NULL AND valor IS NULL)),
    CONSTRAINT bo_lancamento_partida_unica UNIQUE (negocio_id,lado,momento,conta_contabil,natureza,unidade),
    FOREIGN KEY (negocio_id,lado,conta) REFERENCES bo_liquidacao(negocio_id,lado,conta)
);
-- Leitura por conta com igualdade parametrizada e ordem decrescente limitada (GET /api/v1/razao).
CREATE INDEX bo_lancamento_conta_id ON bo_lancamento(conta,id);

-- Append-only: correção só por estorno vinculado à origem (nova linha), nunca UPDATE/DELETE.
CREATE FUNCTION bo_recusar_alteracao() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'APPEND_ONLY' USING ERRCODE = '55000';
END
$$;
CREATE TRIGGER bo_lancamento_append_only BEFORE UPDATE OR DELETE ON bo_lancamento
    FOR EACH ROW EXECUTE FUNCTION bo_recusar_alteracao();
CREATE TRIGGER bo_negocio_fato_append_only BEFORE UPDATE OR DELETE ON bo_negocio_fato
    FOR EACH ROW EXECUTE FUNCTION bo_recusar_alteracao();
