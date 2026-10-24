-- RF08d: expansão aditiva; recuperar por correção para frente/restauração de backup privado.
-- Origem separada preserva fatos append-only. Pendências RF08b sem origem falham fechadas;
-- reconstrução explícita desde a origem do log é necessária, nunca inventar causação.
CREATE TABLE bo_negocio_origem (
    negocio_id varchar(64) PRIMARY KEY REFERENCES bo_negocio_fato(negocio_id),
    evento uuid NOT NULL
);
CREATE TABLE bo_data_movimento (id integer PRIMARY KEY CHECK(id=1), data date NOT NULL);
CREATE TABLE bo_movimento_auditoria (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sub varchar(100) NOT NULL, jti varchar(100) NOT NULL,
    de date NOT NULL, para date NOT NULL CHECK(para>de),
    instante timestamptz NOT NULL DEFAULT now(), liquidacoes integer NOT NULL CHECK(liquidacoes>=0)
);
CREATE TRIGGER bo_movimento_append_only BEFORE UPDATE OR DELETE ON bo_movimento_auditoria
    FOR EACH ROW EXECUTE FUNCTION bo_recusar_alteracao();
CREATE TRIGGER bo_origem_append_only BEFORE UPDATE OR DELETE ON bo_negocio_origem
    FOR EACH ROW EXECUTE FUNCTION bo_recusar_alteracao();
CREATE TABLE bo_outbox (
    id uuid PRIMARY KEY, topico varchar(60) NOT NULL CHECK(topico='liquidacao.eventos.v1'),
    chave varchar(9) NOT NULL, tipo varchar(40) NOT NULL CHECK(tipo='LIQUIDACAO_EFETIVADA'),
    payload text NOT NULL CHECK(length(payload)<=4096), traceparent varchar(55),
    criado_em timestamptz NOT NULL DEFAULT now(), publicado_em timestamptz,
    tentativas integer NOT NULL DEFAULT 0
);
CREATE INDEX bo_outbox_pendente ON bo_outbox(criado_em,id) WHERE publicado_em IS NULL;
CREATE INDEX bo_liquidacao_pendente ON bo_liquidacao(negocio_id,id) WHERE estado='PENDENTE';
CREATE INDEX bo_fato_vencimento ON bo_negocio_fato(data_liquidacao,negocio_id);
CREATE TABLE bo_liquidacao_controle (id integer PRIMARY KEY CHECK(id=1), motivo varchar(40) NOT NULL);
