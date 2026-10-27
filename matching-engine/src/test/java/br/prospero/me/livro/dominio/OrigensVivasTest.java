package br.prospero.me.livro.dominio;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** RF10a: proveniência observada após processamento real, sem políticas de cancelamento manual ou reflexão. */
class OrigensVivasTest {
    private static final LocalDate DIA=LocalDate.of(2026,10,26);
    private int proximo;
    private static BigDecimal d(String texto) {return texto==null?null:new BigDecimal(texto);}
    private UUID id(String texto) {return UUID.nameUUIDFromBytes(texto.getBytes(StandardCharsets.UTF_8));}
    private MotorMatching motor() {
        return new MotorMatching(Map.of("ALFA3",new ParametrosAtivo("ALFA3",d("0.01"),d("10.00"),d("50")),
                "BETA3",new ParametrosAtivo("BETA3",d("0.01"),d("10.00"),d("50"))));
    }
    private Admissao ordem(String conta,Lado lado,TipoOrdem tipo,long q,String limite,String gatilho) {
        int n=++proximo;
        return new Admissao(id("ordem-qa-"+n),id("intencao-qa-"+n),conta,"ALFA3",lado,tipo,
                tipo==TipoOrdem.MARKET?Validade.IOC:Validade.DIA,q,d(limite),
                lado==Lado.COMPRA?d(limite==null?"15.00":limite):null,d(gatilho),DIA);
    }
    private Admissao limit(Lado lado,long q,String preco) {return ordem("CONTA-001",lado,TipoOrdem.LIMIT,q,preco,null);}
    private AdmissaoOco par(TipoOrdem tipoStop,long q) {
        int n=++proximo;UUID intencao=id("oco-qa-"+n);
        return new AdmissaoOco(
                new Admissao(id("tp-qa-"+n),intencao,"CONTA-002","ALFA3",Lado.VENDA,TipoOrdem.LIMIT,Validade.DIA,q,d("12.00"),null,null,DIA),
                new Admissao(id("sl-qa-"+n),intencao,"CONTA-002","ALFA3",Lado.VENDA,tipoStop,Validade.DIA,q,
                        tipoStop==TipoOrdem.STOP_LIMIT?d("8.90"):null,null,d("9.00"),DIA));
    }
    private OrigemOrdem simples(Admissao a) {return new OrigemOrdem(a.tipo(),a.intencao(),a.conta(),a.ticker(),a.sessao(),null,null);}
    private OrigemOrdem oco(Admissao a,AdmissaoOco par) {
        return new OrigemOrdem(a.tipo(),a.intencao(),a.conta(),a.ticker(),a.sessao(),par.takeProfit().ordem(),par.stopLoss().ordem());
    }
    private <T> List<T> tipos(List<Evento> eventos,Class<T> classe) {return eventos.stream().filter(classe::isInstance).map(classe::cast).toList();}

    @Test void limitParcialConservaOrigemESnapshotEImutavel() {
        var m=motor();var venda=limit(Lado.VENDA,3,"10.00");m.processar(venda);
        var antes=m.origensVivas("ALFA3");assertEquals(Map.of(venda.ordem(),simples(venda)),antes);
        assertThrows(UnsupportedOperationException.class,antes::clear);
        m.processar(limit(Lado.COMPRA,1,"10.00"));assertEquals(antes,m.origensVivas("ALFA3"));
        m.processar(limit(Lado.COMPRA,2,"10.00"));assertTrue(m.origensVivas("ALFA3").isEmpty());
        assertEquals(1,antes.size(),"snapshot anterior não acompanha baixas posteriores");
    }

    @Test void consultaIsolaAtivosESemEstadoNaoCriaOrigem() {
        var m=motor();var a=limit(Lado.VENDA,1,"10.00");m.processar(a);
        assertEquals(Set.of(a.ordem()),m.origensVivas("ALFA3").keySet());
        assertTrue(m.origensVivas("BETA3").isEmpty());assertTrue(m.origensVivas("DESCONHECIDO").isEmpty());
    }

    @Test void reentregaNaoSobrescreveOrigemNemRessuscitaTerminal() {
        var m=motor();var a=limit(Lado.VENDA,1,"10.00");m.processar(a);
        assertEquals(simples(a),m.origensVivas("ALFA3").get(a.ordem()));
        var divergente=new Admissao(a.ordem(),id("outra-intencao"),"CONTA-003",a.ticker(),a.lado(),a.tipo(),a.validade(),a.quantidade(),a.precoLimite(),a.precoMaximo(),a.precoGatilho(),a.sessao());
        assertTrue(m.processar(divergente).isEmpty());assertEquals(simples(a),m.origensVivas("ALFA3").get(a.ordem()));
        m.processar(limit(Lado.COMPRA,1,"10.00"));assertTrue(m.processar(a).isEmpty());assertTrue(m.origensVivas("ALFA3").isEmpty());
    }

    @Test void stopLimitConvertidoERepousadoPreservaTipoOriginal() {
        var m=motor();var stop=ordem("CONTA-002",Lado.VENDA,TipoOrdem.STOP_LIMIT,2,"9.50","9.60");m.processar(stop);
        assertEquals(simples(stop),m.origensVivas("ALFA3").get(stop.ordem()));
        m.processar(limit(Lado.COMPRA,1,"9.50"));var eventos=m.processar(limit(Lado.VENDA,1,"9.50"));
        assertEquals(1,tipos(eventos,Evento.StopAcionado.class).size());
        assertTrue(tipos(eventos,Evento.OfertaNoLivro.class).stream().anyMatch(e->e.ordem().equals(stop.ordem())));
        assertEquals(Map.of(stop.ordem(),simples(stop)),m.origensVivas("ALFA3"));
    }

    @Test void stopMarketAcionadoETerminalNaoFicaRetido() {
        var m=motor();var stop=ordem("CONTA-002",Lado.VENDA,TipoOrdem.STOP,2,null,"9.00");m.processar(stop);
        assertEquals(simples(stop),m.origensVivas("ALFA3").get(stop.ordem()));
        m.processar(limit(Lado.COMPRA,1,"9.00"));var eventos=m.processar(limit(Lado.VENDA,1,"9.00"));
        assertTrue(tipos(eventos,Evento.OrdemEncerrada.class).stream().anyMatch(e->e.ordem().equals(stop.ordem())));
        assertTrue(m.origensVivas("ALFA3").isEmpty());
    }

    @Test void parOcoMantemOrigemDasDuasPernasDuranteReducaoELimpaNaExecucao() {
        var m=motor();var par=par(TipoOrdem.STOP_LIMIT,4);m.processarOco(par);
        var esperado=Map.of(par.takeProfit().ordem(),oco(par.takeProfit(),par),par.stopLoss().ordem(),oco(par.stopLoss(),par));
        assertEquals(esperado,m.origensVivas("ALFA3"));
        var parcial=m.processar(limit(Lado.COMPRA,1,"12.00"));assertEquals(1,tipos(parcial,Evento.OrdemReduzida.class).size());
        assertEquals(esperado,m.origensVivas("ALFA3"));
        m.processar(limit(Lado.COMPRA,3,"12.00"));assertTrue(m.origensVivas("ALFA3").isEmpty());
        assertTrue(m.processarOco(par).isEmpty());assertTrue(m.origensVivas("ALFA3").isEmpty());
    }

    @Test void ocoCapturadoEDissolvidoConservaVinculoNaStopLimitSobrevivente() {
        var m=motor();var par=par(TipoOrdem.STOP_LIMIT,4);m.processarOco(par);
        assertEquals(2,m.origensVivas("ALFA3").size());m.processar(limit(Lado.COMPRA,1,"12.00"));
        m.processar(limit(Lado.COMPRA,1,"9.00"));var eventos=m.processar(limit(Lado.VENDA,1,"9.00"));
        assertTrue(tipos(eventos,Evento.OrdemEncerrada.class).stream().anyMatch(e->e.ordem().equals(par.takeProfit().ordem())&&e.motivo()==MotivoEncerramento.CANCELADA_OCO));
        assertEquals(Map.of(par.stopLoss().ordem(),oco(par.stopLoss(),par)),m.origensVivas("ALFA3"));
        m.encerrarSessao("ALFA3",DIA);assertTrue(m.origensVivas("ALFA3").isEmpty());
    }

    @Test void ocoComStopMarketNaoRetemOrigemDepoisDaCascata() {
        var m=motor();var par=par(TipoOrdem.STOP,2);m.processarOco(par);assertEquals(2,m.origensVivas("ALFA3").size());
        m.processar(limit(Lado.COMPRA,1,"9.00"));m.processar(limit(Lado.VENDA,1,"9.00"));assertTrue(m.origensVivas("ALFA3").isEmpty());
    }

    @Test void parComUmaPernaJaVistaNaoTransformaOrigemSimplesEmOco() {
        var m=motor();var par=par(TipoOrdem.STOP_LIMIT,2);m.processar(par.takeProfit());
        assertEquals(simples(par.takeProfit()),m.origensVivas("ALFA3").get(par.takeProfit().ordem()));
        assertFalse(tipos(m.processarOco(par),Evento.OrdemRejeitada.class).isEmpty());
        assertEquals(Map.of(par.takeProfit().ordem(),simples(par.takeProfit())),m.origensVivas("ALFA3"));
    }

    @Test void rejeicaoNaoRegistraOrigemNemModificaOrdemViva() {
        var m=motor();var aceita=limit(Lado.VENDA,1,"10.00");m.processar(aceita);
        assertEquals(1,m.origensVivas("ALFA3").size());var invalida=limit(Lado.VENDA,1,"10.001");
        assertEquals(1,tipos(m.processar(invalida),Evento.OrdemRejeitada.class).size());
        assertEquals(Map.of(aceita.ordem(),simples(aceita)),m.origensVivas("ALFA3"));
    }

    @Test void tetoDeAtivasEReutilizacaoDeVagaNaoRetemMetadadosTerminais() {
        var m=motor();var primeiras=new ArrayList<Admissao>();
        for(int i=0;i<1000;i++){var a=limit(Lado.VENDA,1,"12.00");primeiras.add(a);m.processar(a);}
        assertEquals(1000,m.origensVivas("ALFA3").size());var excedente=limit(Lado.VENDA,1,"12.00");
        assertEquals(1,tipos(m.processar(excedente),Evento.OrdemRejeitada.class).size());assertFalse(m.origensVivas("ALFA3").containsKey(excedente.ordem()));
        m.processar(ordem("CONTA-003",Lado.COMPRA,TipoOrdem.LIMIT,1,"12.00",null));assertEquals(999,m.origensVivas("ALFA3").size());
        assertFalse(m.origensVivas("ALFA3").containsKey(primeiras.getFirst().ordem()));
        var nova=limit(Lado.VENDA,1,"12.00");m.processar(nova);assertEquals(1000,m.origensVivas("ALFA3").size());
        m.encerrarSessao("ALFA3",DIA);assertTrue(m.origensVivas("ALFA3").isEmpty());
    }

    @Test void tetoDePendentesNaoAcrescentaOrigemNaRejeicao() {
        var m=motor();for(int i=0;i<500;i++)m.processar(ordem("CONTA-002",Lado.VENDA,TipoOrdem.STOP,1,null,"9.00"));
        assertEquals(500,m.origensVivas("ALFA3").size());var excesso=ordem("CONTA-002",Lado.VENDA,TipoOrdem.STOP,1,null,"9.00");
        assertEquals(1,tipos(m.processar(excesso),Evento.OrdemRejeitada.class).size());assertEquals(500,m.origensVivas("ALFA3").size());
        assertFalse(m.origensVivas("ALFA3").containsKey(excesso.ordem()));m.encerrarSessao("ALFA3",DIA);assertTrue(m.origensVivas("ALFA3").isEmpty());
    }

    @Test void cascataTrintaEDoisELimiteNaoDeixamOrigemOrfa() {
        var m=motor();for(int i=0;i<33;i++)m.processar(ordem("CONTA-002",Lado.VENDA,TipoOrdem.STOP,1,null,"9.00"));
        assertEquals(33,m.origensVivas("ALFA3").size());m.processar(limit(Lado.COMPRA,1,"9.00"));
        var eventos=m.processar(limit(Lado.VENDA,1,"9.00"));assertEquals(32,tipos(eventos,Evento.StopAcionado.class).size());
        assertTrue(tipos(eventos,Evento.OrdemEncerrada.class).stream().anyMatch(e->e.motivo()==MotivoEncerramento.LIMITE_CASCATA));
        assertTrue(m.origensVivas("ALFA3").isEmpty());
    }

    @Test void limiteDuzentosECinquentaESeisNegociosMantemSoPassivaNaoConsumida() {
        var m=motor();var passivas=new ArrayList<Admissao>();
        for(int i=0;i<257;i++){var a=limit(Lado.VENDA,1,"10.00");passivas.add(a);m.processar(a);}
        assertEquals(257,m.origensVivas("ALFA3").size());
        var eventos=m.processar(ordem("CONTA-003",Lado.COMPRA,TipoOrdem.LIMIT,257,"10.00",null));
        assertEquals(256,tipos(eventos,Evento.Negocio.class).size());
        assertEquals(Map.of(passivas.getLast().ordem(),simples(passivas.getLast())),m.origensVivas("ALFA3"));
    }

    @Test void fechamentoLimpaApenasAtivoAlvoEReentregaNaoRessuscita() {
        var m=motor();var a=limit(Lado.VENDA,1,"12.00");m.processar(a);
        var b=new Admissao(id("beta"),id("intencao-beta"),"CONTA-002","BETA3",Lado.COMPRA,TipoOrdem.LIMIT,Validade.DIA,1,d("9.00"),d("9.00"),DIA);m.processar(b);
        assertEquals(1,m.origensVivas("ALFA3").size());assertEquals(1,m.origensVivas("BETA3").size());
        m.encerrarSessao("ALFA3",DIA);assertTrue(m.origensVivas("ALFA3").isEmpty());assertEquals(Map.of(b.ordem(),simples(b)),m.origensVivas("BETA3"));
        assertTrue(m.processar(a).isEmpty());assertTrue(m.encerrarSessao("ALFA3",DIA).isEmpty());assertTrue(m.origensVivas("ALFA3").isEmpty());
    }

    @Test void replayReconstruiMesmasOrigensVivasSemHistoricoExtra() {
        var primeiro=motor();var replay=motor();var stop=ordem("CONTA-002",Lado.VENDA,TipoOrdem.STOP_LIMIT,2,"9.50","9.60");
        var comandos=List.of(stop,limit(Lado.COMPRA,1,"9.50"),limit(Lado.VENDA,1,"9.50"));
        for(var comando:comandos){assertEquals(primeiro.processar(comando),replay.processar(comando));assertEquals(primeiro.origensVivas("ALFA3"),replay.origensVivas("ALFA3"));}
        assertEquals(Map.of(stop.ordem(),simples(stop)),replay.origensVivas("ALFA3"));
    }

    @Test void remocaoIntermediariaDoLivroPreservaFifoEProgressoDasDemais() {
        var livro=new Livro();var a=new OfertaViva(id("fifo-a"),"CONTA-001",Lado.VENDA,d("10.00"),1,3,0);
        var b=new OfertaViva(id("fifo-b"),"CONTA-002",Lado.VENDA,d("10.00"),2,3,1);
        var c=new OfertaViva(id("fifo-c"),"CONTA-003",Lado.VENDA,d("10.00"),3,3,0);
        livro.repousar(a);livro.repousar(b);livro.repousar(c);assertSame(b,livro.remover(b.ordem));assertEquals(2,b.restante());
        assertNull(livro.remover(b.ordem));assertSame(a,livro.melhor(Lado.VENDA));livro.removerCabeca(a);assertSame(c,livro.melhor(Lado.VENDA));
        livro.remover(c.ordem);assertNull(livro.melhor(Lado.VENDA));assertFalse(livro.contem(b.ordem));
    }

    @Test void removerStopPendentePreservaIndicesEOrdemDosSobreviventes() {
        var stops=new StopsPendentes();var a=ordem("CONTA-001",Lado.VENDA,TipoOrdem.STOP,1,null,"9.00");
        var b=ordem("CONTA-002",Lado.VENDA,TipoOrdem.STOP,1,null,"9.00");var c=ordem("CONTA-003",Lado.VENDA,TipoOrdem.STOP,1,null,"9.00");
        stops.guardar(a,1);stops.guardar(b,2);stops.guardar(c,3);stops.remover(b.ordem());stops.remover(b.ordem());
        assertEquals(2,stops.quantidade());assertEquals(List.of(a,c),stops.retirarAcionados(d("9.00")));assertEquals(0,stops.quantidade());
        assertTrue(stops.retirarTodas().isEmpty());
    }
}
