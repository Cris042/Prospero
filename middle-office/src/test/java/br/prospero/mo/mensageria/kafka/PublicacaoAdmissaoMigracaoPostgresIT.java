package br.prospero.mo.mensageria.kafka;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Migração real 7→8 em schemas próprios, sem limpar banco/volume ou fabricar ACK. */
class PublicacaoAdmissaoMigracaoPostgresIT {
    private static Connection conexao() throws Exception {
        return DriverManager.getConnection(System.getenv("MATCHING_TEST_DB_URL"), System.getenv("MATCHING_TEST_DB_USER"), System.getenv("MATCHING_TEST_DB_PASSWORD"));
    }
    private static Flyway flyway(String schema, String alvo) {
        return Flyway.configure().dataSource(System.getenv("MATCHING_TEST_DB_URL"),System.getenv("MATCHING_TEST_DB_USER"),System.getenv("MATCHING_TEST_DB_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target(alvo).load();
    }
    private interface Cenario { void executar(String schema, Connection c) throws Exception; }
    private void banco(Cenario cenario) throws Exception {
        String schema="qa_migracao_admissao_"+UUID.randomUUID().toString().replace("-", "");
        try(var c=conexao();var s=c.createStatement()) {
            flyway(schema,"7").migrate();s.execute("SET search_path TO "+schema);
            cenario.executar(schema,c);
        } finally {
            try(var c=conexao();var s=c.createStatement()){s.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
        }
    }
    private UUID reserva(Connection c, boolean legada) throws Exception {
        UUID id=UUID.randomUUID();
        try(var s=c.prepareStatement("INSERT INTO decisao_risco(intencao,evento,conta,ticker,lado,resultado,valor_reservado,versao_parametros,decidida_em) VALUES(?,?,'CONTA-001','ALFA3','COMPRA','RESERVADA',10,1,now())")) {s.setObject(1,id);s.setObject(2,id);s.executeUpdate();}
        try(var s=c.prepareStatement("INSERT INTO reserva(intencao,conta,ticker,recurso,valor,estado,criada_em,admissao) VALUES(?,'CONTA-001','ALFA3','CAIXA',10,'ATIVA',now(),?)")){s.setObject(1,id);s.setObject(2,legada?null:id);s.executeUpdate();}
        return id;
    }
    private void outbox(Connection c,UUID id,String topico,String tipo,String chave,String payload,boolean publicada) throws Exception {
        try(var s=c.prepareStatement("INSERT INTO outbox(id,topico,tipo,chave,payload,traceparent,criado_em,publicado_em) VALUES(?,?,?,?,?,'00-11111111111111111111111111111111-2222222222222222-01','2026-10-26T10:00:00Z',?)")) {
            s.setObject(1,id);s.setString(2,topico);s.setString(3,tipo);s.setString(4,chave);s.setString(5,payload);s.setObject(6,publicada?java.time.OffsetDateTime.parse("2026-10-26T10:01:00Z"):null);s.executeUpdate();
        }
    }
    private long total(Connection c,String tabela) throws Exception {try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+tabela)){r.next();return r.getLong(1);}}

    @Test void backfillCopiaBytesHeaderECriacaoComAckRealOuPendenteSemInventarLegado() throws Exception {
        banco((schema,c)-> {
            UUID publicada=reserva(c,false),pendente=reserva(c,false);reserva(c,false);reserva(c,true);
            String payload="{\"ação\": 1, \"ordem\":\""+publicada+"\"}";
            outbox(c,publicada,"matching.comandos.v1","ORDEM_ADMITIDA","ALFA3",payload,true);
            outbox(c,pendente,"matching.comandos.v1","ORDEM_ADMITIDA","ALFA3","{}",false);
            outbox(c,UUID.randomUUID(),"risco.resultados.v1","RESULTADO_RISCO","CONTA-001","{}",true);
            flyway(schema,"8").migrate();assertEquals(2,total(c,"admissao_publicacao"));
            try(var s=c.prepareStatement("SELECT a.payload=o.payload,a.traceparent=o.traceparent,a.criada_em=o.criado_em,a.confirmada_em IS NOT DISTINCT FROM o.publicado_em FROM admissao_publicacao a JOIN outbox o ON o.id=a.admissao WHERE a.admissao=?")) {
                for(UUID id:java.util.List.of(publicada,pendente)){s.setObject(1,id);try(var r=s.executeQuery()){assertTrue(r.next());for(int n=1;n<=4;n++)assertTrue(r.getBoolean(n));}}
            }
            try(var s=c.prepareStatement("SELECT payload FROM admissao_publicacao WHERE admissao=?")){s.setObject(1,publicada);try(var r=s.executeQuery()){r.next();assertArrayEquals(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),r.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
        });
    }

    @Test void backfillIncompativelFalhaSemFiltrarVinculoNemCopiarDadosParciais() throws Exception {
        for(String campo:java.util.List.of("topico","tipo","chave","payload")) banco((schema,c)-> {
            UUID id=reserva(c,false);
            outbox(c,id,campo.equals("topico")?"qa.errado":"matching.comandos.v1",campo.equals("tipo")?"OUTRO":"ORDEM_ADMITIDA",campo.equals("chave")?"BETA3":"ALFA3",campo.equals("payload")?"á".repeat(8192)+"a":"{}",true);
            var erro=assertThrows(org.flywaydb.core.api.FlywayException.class,()->flyway(schema,"8").migrate(),campo);
            assertFalse(erro.getMessage().contains("á".repeat(100)),"erro não expõe payload");
            try(var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('admissao_publicacao') IS NULL")){r.next();assertTrue(r.getBoolean(1),"DDL/backfill devem reverter juntos");}
            assertEquals(1,total(c,"reserva"));assertEquals(1,total(c,"outbox"));
        });
    }

    @Test void limiteUtf8ExatoAceita16384BytesERecusa16385ComFkReal() throws Exception {
        banco((schema,c)-> {
            UUID id=reserva(c,false);String payload="á".repeat(8192);
            outbox(c,id,"matching.comandos.v1","ORDEM_ADMITIDA","ALFA3",payload,false);
            flyway(schema,"8").migrate();
            try(var s=c.createStatement();var r=s.executeQuery("SELECT octet_length(payload) FROM admissao_publicacao")){r.next();assertEquals(16384,r.getInt(1));}
            try(var s=c.prepareStatement("UPDATE admissao_publicacao SET payload=? WHERE admissao=?")){s.setString(1,payload+"a");s.setObject(2,id);assertThrows(java.sql.SQLException.class,s::executeUpdate);}
            try(var s=c.prepareStatement("DELETE FROM reserva WHERE admissao=?")){s.setObject(1,id);assertThrows(java.sql.SQLException.class,s::executeUpdate);}
            try(var s=c.prepareStatement("INSERT INTO admissao_publicacao(admissao,topico,chave,tipo,payload,criada_em) VALUES(?,'matching.comandos.v1','ALFA3','ORDEM_ADMITIDA','{}',now())")){s.setObject(1,UUID.randomUUID());assertThrows(java.sql.SQLException.class,s::executeUpdate);}
            assertEquals(1,total(c,"admissao_publicacao"));
        });
    }
}
