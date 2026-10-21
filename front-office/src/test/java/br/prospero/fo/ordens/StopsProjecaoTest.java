package br.prospero.fo.ordens;

import br.prospero.fo.ordens.dominio.*;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import br.prospero.fo.ordens.helper.ProjetarMatching;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class StopsProjecaoTest {
    private static final UUID ID=UUID.fromString("80ef3249-7815-4560-a260-63ecf28bd581");
    private Ordem ordem(TipoOrdem tipo) {
        return new Ordem(ID,"CONTA-002","ALFA3",LadoOrdem.COMPRA,tipo,Validade.DIA,10,
                tipo==TipoOrdem.STOP_LIMIT ? new BigDecimal("10.00") : null,new BigDecimal("10.00"),null,null,ID,
                EstadoOrdem.RECEBIDA,LocalDate.of(2026,10,18),Instant.parse("2026-10-18T12:00:00Z"),null);
    }
    private EventoMatching evento(String tipo,String campos) {
        String s="""
                {"id":"80ef3249-7815-4560-a260-63ecf28bd581","tipo":"%s","versao":2,"origem":"matching-engine",
                "correlacao":"e4b8a895-e56a-4430-925b-29236393db61","causacao":"ed42a1e3-9ef9-4ba5-87b4-a0bcf224a316",
                "ocorridoEm":"2026-10-18T12:00:00Z","ticker":"ALFA3","dataSessao":"2026-10-18","traceparent":null,
                "dados":{"ordem":"%s","intencao":"%s","conta":"CONTA-002",%s}}
                """.formatted(tipo, ID, ID, campos);
        return EnvelopeMatching.evento(s.getBytes(StandardCharsets.UTF_8),"ALFA3");
    }
    private Ordem aplicar(Ordem o,EventoMatching e) {
        return ProjetarMatching.aplicar(o,e,new EventoMatching.Alvo(ID,ID,"CONTA-002"),new BigDecimal("0.01"),1);
    }
    @Test void aceiteAntesDoRiscoEsperaGatilhoEAcionamentoAbrePreservandoContratoOriginal() {
        Ordem o=ordem(TipoOrdem.STOP);
        o=aplicar(o,evento("ORDEM_ACEITA","\"sequencia\":1"));
        assertEquals("AGUARDANDO_GATILHO",o.estado().name());
        o=aplicar(o,evento("STOP_PENDENTE","\"precoGatilho\":\"10.00\""));
        assertEquals("AGUARDANDO_GATILHO",o.estado().name());
        o=aplicar(o,evento("STOP_ACIONADO","\"tipoExecutavel\":\"MARKET\",\"sequencia\":2"));
        assertEquals(EstadoOrdem.ABERTA,o.estado());
        assertEquals(TipoOrdem.STOP,o.tipo()); assertEquals(Validade.DIA,o.validade());
        assertEquals(o,aplicar(o,evento("ORDEM_ACEITA","\"sequencia\":1")));
        assertEquals(o,aplicar(o,evento("STOP_PENDENTE","\"precoGatilho\":\"10.00\"")));
    }
    @Test void acionamentoImediatoDispensaPendenteEStopLimitPodeRepousar() {
        Ordem o=aplicar(ordem(TipoOrdem.STOP_LIMIT),evento("STOP_ACIONADO","\"tipoExecutavel\":\"LIMIT\",\"sequencia\":2"));
        o=aplicar(o,evento("OFERTA_NO_LIVRO","\"lado\":\"COMPRA\",\"preco\":\"10.00\",\"restante\":10"));
        assertEquals(EstadoOrdem.ABERTA,o.estado()); assertEquals(TipoOrdem.STOP_LIMIT,o.tipo());
    }
    @Test void pendenteNaoPodeNegociarNemRepousarAntesDeAtivacao() {
        Ordem o=ordem(TipoOrdem.STOP_LIMIT);
        assertThrows(MatchingIncoerenteException.class,()->aplicar(o,new EventoMatching(UUID.randomUUID(),UUID.randomUUID(),"ALFA3",o.dataSessao(),"h",
                new EventoMatching.Negocio("ALFA3-2026-10-18-1",new BigDecimal("10.00"),1,
                    new EventoMatching.Alvo(ID,ID,"CONTA-002"),new EventoMatching.Alvo(UUID.randomUUID(),UUID.randomUUID(),"CONTA-003"),LadoOrdem.COMPRA))));
        assertThrows(MatchingIncoerenteException.class,()->aplicar(o,evento("OFERTA_NO_LIVRO","\"lado\":\"COMPRA\",\"preco\":\"10.00\",\"restante\":10")));
    }
    @Test void limiteCascataCancelaPendenteSemAtivacao() {
        Ordem o=aplicar(ordem(TipoOrdem.STOP),evento("ORDEM_ENCERRADA","\"motivo\":\"LIMITE_CASCATA\",\"executada\":0,\"cancelada\":10"));
        assertEquals(EstadoOrdem.CANCELADA,o.estado()); assertEquals(10,o.quantidadeCancelada());
    }
    @Test void stopJaAcionadoNaoPodeSerRejeitadoPorLimiteDePendentesNemRejeicaoDeAdmissao() {
        Ordem ativa=aplicar(ordem(TipoOrdem.STOP),evento("STOP_ACIONADO","\"tipoExecutavel\":\"MARKET\",\"sequencia\":2"));
        for(String motivo:new String[]{"LIMITE_STOPS_PENDENTES","COMBINACAO_INVALIDA"}) {
            assertThrows(MatchingIncoerenteException.class,()->aplicar(ativa,evento("ORDEM_REJEITADA","\"motivo\":\""+motivo+"\"")));
        }
    }

}
