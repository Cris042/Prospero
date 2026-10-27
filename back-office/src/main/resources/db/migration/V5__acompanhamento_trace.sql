-- RF09/ADR-017: expansão compatível. Imagem anterior ignora a coluna; não retirar fatos.
-- Recuperação por nova migração ou backup privado compatível; restauração não presumida.
ALTER TABLE bo_negocio_origem ADD COLUMN traceparent VARCHAR(55);
ALTER TABLE bo_outbox DROP CONSTRAINT bo_outbox_topico_check;
ALTER TABLE bo_outbox DROP CONSTRAINT bo_outbox_tipo_check;
ALTER TABLE bo_outbox ADD CONSTRAINT bo_outbox_contrato_check CHECK (
    (topico='liquidacao.eventos.v1' AND tipo='LIQUIDACAO_EFETIVADA') OR
    (topico='acompanhamento.eventos.v1' AND tipo='NEGOCIO_CONTABILIZADO')
);
