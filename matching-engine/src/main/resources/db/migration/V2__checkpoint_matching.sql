-- Somente metadados aditivos; o livro é reconstruído integralmente do log Kafka, desde offset zero.
-- Recuperação: nova migração corretiva ou restauração coordenada do banco E logs preservados.
-- Nunca apagar estes registros para contornar perda/recriação do Kafka: isso reutilizaria IDs financeiros.
CREATE TABLE matching_log (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    topico_comandos_id VARCHAR(36) NOT NULL,
    topico_eventos_id VARCHAR(36) NOT NULL,
    origem BIGINT NOT NULL CHECK (origem = 0),
    sessao DATE
);
CREATE TABLE matching_checkpoint (
    particao INTEGER PRIMARY KEY CHECK (particao BETWEEN 0 AND 2),
    ultimo_publicado BIGINT NOT NULL DEFAULT -1 CHECK (ultimo_publicado BETWEEN -1 AND 9999)
);
COMMENT ON TABLE matching_log IS 'Identidade imutável dos logs e única sessão do laboratório RF05b';
COMMENT ON TABLE matching_checkpoint IS 'Último comando com todos os eventos confirmados; CAS sob posse advisory dedicada';
