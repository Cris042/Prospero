-- RF07: expansão aditiva. Recuperação por imagem anterior/correção para frente; não apagar projeções parcialmente.
CREATE TABLE bo_log (id integer PRIMARY KEY CHECK (id=1), topico_id varchar(22) NOT NULL);
CREATE TABLE bo_checkpoint (
    particao integer PRIMARY KEY CHECK (particao BETWEEN 0 AND 2),
    proximo_offset bigint NOT NULL CHECK (proximo_offset>=0),
    log_id integer NOT NULL DEFAULT 1 REFERENCES bo_log(id)
);
CREATE TABLE bo_transporte (
    particao integer NOT NULL REFERENCES bo_checkpoint(particao),
    registro_offset bigint NOT NULL CHECK (registro_offset>=0),
    assinatura char(64) NOT NULL,
    PRIMARY KEY (particao,registro_offset)
);
CREATE TABLE bo_evento (id uuid PRIMARY KEY, assinatura char(64) NOT NULL);
CREATE TABLE bo_negocio (id varchar(64) PRIMARY KEY, assinatura char(64) NOT NULL);
CREATE TABLE bo_cotacao (
    ticker varchar(6) PRIMARY KEY CHECK (ticker IN ('ALFA3','BETA3','GAMA3','DELTA3','OMEGA3')),
    preco numeric(9,2) CHECK (preco>0 AND preco<=1000000.00),
    sessao date,
    encerrada boolean NOT NULL DEFAULT false,
    revisao bigint NOT NULL DEFAULT 0 CHECK (revisao>=0),
    atualizada_em timestamptz
);
CREATE TABLE bo_posicao (
    conta varchar(9) NOT NULL CHECK (conta IN ('CONTA-001','CONTA-002','CONTA-003','CONTA-004')),
    ticker varchar(6) NOT NULL REFERENCES bo_cotacao(ticker),
    quantidade bigint NOT NULL DEFAULT 0 CHECK (quantidade<>('-9223372036854775808'::bigint)),
    medio_n text,
    medio_d text,
    realizado_n text NOT NULL DEFAULT '0',
    realizado_d text NOT NULL DEFAULT '1',
    PRIMARY KEY (conta,ticker),
    CHECK ((quantidade=0 AND medio_n IS NULL AND medio_d IS NULL)
        OR (quantidade<>0 AND medio_n IS NOT NULL AND medio_d IS NOT NULL)),
    CHECK (medio_n IS NULL OR (length(medio_n)<=39457 AND medio_n ~ '^[1-9][0-9]*$')),
    CHECK (medio_d IS NULL OR (length(medio_d)<=39457 AND medio_d ~ '^[1-9][0-9]*$')),
    CHECK (length(realizado_n)<=39458 AND realizado_n ~ '^(0|-?[1-9][0-9]*)$'),
    CHECK (length(realizado_d)<=39457 AND realizado_d ~ '^[1-9][0-9]*$')
);
INSERT INTO bo_cotacao(ticker) VALUES ('ALFA3'),('BETA3'),('GAMA3'),('DELTA3'),('OMEGA3');
INSERT INTO bo_posicao(conta,ticker)
SELECT c.conta,a.ticker FROM (VALUES ('CONTA-001'),('CONTA-002'),('CONTA-003'),('CONTA-004')) c(conta)
CROSS JOIN bo_cotacao a;
