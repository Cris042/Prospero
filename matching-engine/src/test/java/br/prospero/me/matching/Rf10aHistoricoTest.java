package br.prospero.me.matching;

import br.prospero.me.matching.helper.EnvelopeMatching;
import br.prospero.me.matching.helper.SessaoMatching;
import br.prospero.me.matching.kafka.PublicacaoMatching;
import br.prospero.me.livro.dominio.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Vetores congelados em 625cf1d, antes do comportamento RF10a (stub de consulta inócuo). Nunca regenerar no teste. */
class Rf10aHistoricoTest {
    private List<String> linhas(String nome) throws Exception {
        try(var input=getClass().getResourceAsStream("/rf10a/"+nome)) {
            assertNotNull(input,nome);
            return new String(input.readAllBytes(),StandardCharsets.UTF_8).lines().toList();
        }
    }
    private List<List<byte[]>> envelopes(int versao) throws Exception {
        var sessao=new SessaoMatching();var lotes=new ArrayList<List<byte[]>>();
        for(String input:linhas("historico-v"+versao+"-comandos.ndjson"))
            lotes.add(sessao.processar(EnvelopeMatching.comando(input.getBytes(StandardCharsets.UTF_8),"ALFA3")));
        return lotes;
    }
    private void conferirBytes(List<String> esperado,List<List<byte[]>> lotes) {
        int indice=0;
        for(int comando=0;comando<lotes.size();comando++)for(byte[] evento:lotes.get(comando)) {
            assertTrue(indice<esperado.size(),"evento novo inesperado");
            String linha=esperado.get(indice++);int separador=linha.indexOf('\t');
            assertEquals(Integer.toString(comando),linha.substring(0,separador));
            assertArrayEquals(linha.substring(separador+1).getBytes(StandardCharsets.UTF_8),evento);
        }
        assertEquals(esperado.size(),indice,"evento histórico ausente");
    }
    @Test void replayPreservaBytesHistoricosV1aV4PorComando() throws Exception {
        for(int v=1;v<=4;v++) {
            var esperado=linhas("historico-v"+v+"-envelopes.ndjson");
            conferirBytes(esperado,envelopes(v));
            conferirBytes(esperado,envelopes(v));
        }
    }
    @Test void nucleoPreservaEventosHistoricosV1aV4() throws Exception {
        for(int v=1;v<=4;v++) {
            var motor=new MotorMatching(Map.of("ALFA3",new ParametrosAtivo("ALFA3",new BigDecimal("0.01"),new BigDecimal("10.00"),BigDecimal.TEN)));
            var eventos=new ArrayList<String>();int i=0;
            for(String input:linhas("historico-v"+v+"-comandos.ndjson")) {
                var c=EnvelopeMatching.comando(input.getBytes(StandardCharsets.UTF_8),"ALFA3");
                var lote=c.encerramento()!=null?motor.encerrarSessao(c.encerramento().ticker(),c.encerramento().sessao()):
                        c.oco()?motor.processarOco(new AdmissaoOco(c.admissao(),c.stopLoss())):motor.processar(c.admissao());
                for(var evento:lote)eventos.add(i+"\t"+evento);i++;
            }
            assertEquals(linhas("historico-v"+v+"-eventos.txt"),eventos,"eventos congelados v"+v);
        }
    }
    @Test void falhaDePublicacaoParcialNaoAvancaCheckpointEReplayMantemVetores() throws Exception {
        for(int v=1;v<=4;v++) {
            var lote=envelopes(v).stream().filter(l->l.size()>=2).findFirst().orElseThrow();
            var enviados=new AtomicInteger();var checkpoints=new AtomicInteger();
            assertThrows(IOException.class,()->PublicacaoMatching.publicar(lote,e->{
                if(enviados.incrementAndGet()==2)throw new IOException("ack parcial RF10a QA");
            },checkpoints::incrementAndGet));
            assertEquals(0,checkpoints.get());
            conferirBytes(linhas("historico-v"+v+"-envelopes.ndjson"),envelopes(v));
        }
    }
}
