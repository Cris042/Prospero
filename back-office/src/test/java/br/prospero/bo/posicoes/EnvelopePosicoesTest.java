package br.prospero.bo.posicoes;

import br.prospero.bo.posicoes.dominio.EventoMatching;
import br.prospero.bo.posicoes.dominio.MensagemInvalidaException;
import br.prospero.bo.posicoes.dominio.ProjecaoIncoerenteException;
import br.prospero.bo.posicoes.helper.CoerenciaMatching;
import br.prospero.bo.posicoes.helper.EnvelopeMatching;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EnvelopePosicoesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void negocioTransportaDoisParticipantesComPrecoExatoESemDependenciaDoFo() throws Exception {
        EventoMatching evento = ler(negocio("ALFA3", "10.01", 3));
        CoerenciaMatching.validar(evento);
        EventoMatching.Negocio n = assertInstanceOf(EventoMatching.Negocio.class, evento.detalhe());
        assertEquals(new BigDecimal("10.01"), n.preco());
        assertEquals(3, n.quantidade());
        assertEquals("CONTA-002", n.compra().conta());
        assertEquals("CONTA-003", n.venda().conta());
        assertNotEquals(n.compra().ordem(), n.venda().ordem());
    }

    @Test
    void chaveOuCamposExtrasInvalidamEnvelope() throws Exception {
        Map<String, Object> e = negocio("ALFA3", "10.01", 3);
        assertThrows(MensagemInvalidaException.class, () -> EnvelopeMatching.evento(JSON.writeValueAsBytes(e), "BETA3"));
        e.put("campoExtra", "nao-autorizado");
        assertThrows(MensagemInvalidaException.class, () -> ler(e));
    }

    @Test
    void camposDuplicadosETokensAposJsonSaoRejeitados() throws Exception {
        String valido = JSON.writeValueAsString(negocio("ALFA3", "10.01", 3));
        String duplicado = valido.replace("\"origem\":\"matching-engine\"",
                "\"origem\":\"matching-engine\",\"origem\":\"matching-engine\"");
        assertThrows(MensagemInvalidaException.class,
                () -> EnvelopeMatching.evento(duplicado.getBytes(StandardCharsets.UTF_8), "ALFA3"));
        assertThrows(MensagemInvalidaException.class,
                () -> EnvelopeMatching.evento((valido + " {}").getBytes(StandardCharsets.UTF_8), "ALFA3"));
    }

    @Test
    void contaDeMedicao005EhAceitaComoCompradoraERecusaContaDesconhecida() throws Exception {
        Map<String, Object> medicao = negocio("ALFA3", "10.01", 3);
        dados(medicao).put("contaCompra", "CONTA-005");
        EventoMatching.Negocio n = assertInstanceOf(EventoMatching.Negocio.class, ler(medicao).detalhe());
        assertEquals("CONTA-005", n.compra().conta());
        Map<String, Object> desconhecida = negocio("ALFA3", "10.01", 3);
        dados(desconhecida).put("contaCompra", "CONTA-999");
        assertThrows(MensagemInvalidaException.class, () -> ler(desconhecida));
    }

    @Test
    void precoExcessivoQuantidadeContaEVersaoDesconhecidasFalhamFechadas() {
        for (Map<String, Object> e : List.of(negocio("ALFA3", "1e999999", 3),
                negocio("ALFA3", "10.01", Long.MAX_VALUE), negocio("ALFA3", "10.001", 3))) {
            assertThrows(MensagemInvalidaException.class, () -> ler(e));
        }
        Map<String, Object> conta = negocio("ALFA3", "10.01", 3);
        dados(conta).put("contaCompra", "CONTA-999");
        MensagemInvalidaException erro = assertThrows(MensagemInvalidaException.class, () -> ler(conta));
        assertFalse(erro.getMessage().contains("CONTA-999"));
        Map<String, Object> versao = negocio("ALFA3", "10.01", 3);
        versao.put("versao", 999);
        assertThrows(MensagemInvalidaException.class, () -> ler(versao));
    }

    @Test
    void tamanhoDoEnvelopeELadosDaMesmaOrdemSaoLimitados() {
        assertThrows(MensagemInvalidaException.class, () -> EnvelopeMatching.evento(new byte[16385], "ALFA3"));
        Map<String, Object> e = negocio("ALFA3", "10.01", 3);
        dados(e).put("ordemVenda", dados(e).get("ordemCompra"));
        assertThrows(MensagemInvalidaException.class, () -> ler(e));
    }

    @Test
    void correlacaoPrecisaIdentificarAdmissaoDoAgressor() throws Exception {
        Map<String, Object> e = negocio("ALFA3", "10.01", 3);
        e.put("correlacao", UUID.randomUUID().toString());
        EventoMatching evento = ler(e);
        assertThrows(ProjecaoIncoerenteException.class, () -> CoerenciaMatching.validar(evento));
    }

    @Test
    void loteETickOmegaSaoValidadosIndependentementeDoProdutor() throws Exception {
        CoerenciaMatching.validar(ler(negocio("OMEGA3", "50.05", 100)));
        for (Map<String, Object> e : List.of(negocio("OMEGA3", "50.01", 100), negocio("OMEGA3", "50.05", 1))) {
            EventoMatching evento = ler(e);
            assertThrows(ProjecaoIncoerenteException.class, () -> CoerenciaMatching.validar(evento));
        }
    }

    @Test
    void marcoDeEncerramentoExigeIdECausacaoDeterministicos() throws Exception {
        LocalDate dia = LocalDate.of(2026, 10, 23);
        UUID causa = EventoMatching.encerramento("ALFA3", dia);
        Map<String, Object> e = negocio("ALFA3", "10.01", 3);
        e.put("tipo", "SESSAO_ENCERRADA"); e.put("versao", 4); e.put("causacao", causa.toString());
        e.put("correlacao", causa.toString()); e.put("dados", Map.of("expiradas", 0));
        e.put("id", UUID.nameUUIDFromBytes(("matching:" + causa + ":0").getBytes(StandardCharsets.UTF_8)).toString());
        CoerenciaMatching.validar(ler(e));
        e.put("id", UUID.randomUUID().toString());
        EventoMatching divergente = ler(e);
        assertThrows(ProjecaoIncoerenteException.class, () -> CoerenciaMatching.validar(divergente));
    }

    private static EventoMatching ler(Map<String, Object> e) throws Exception {
        return EnvelopeMatching.evento(JSON.writeValueAsBytes(e), e.get("ticker").toString());
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> dados(Map<String, Object> e) {
        return (Map<String, Object>) e.get("dados");
    }
    private static Map<String, Object> negocio(String ticker, String preco, long quantidade) {
        UUID intencao = UUID.randomUUID();
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("negocio", ticker + "-2026-10-23-1"); dados.put("preco", preco); dados.put("quantidade", quantidade);
        dados.put("ordemCompra", UUID.randomUUID().toString()); dados.put("intencaoCompra", intencao.toString());
        dados.put("contaCompra", "CONTA-002"); dados.put("ordemVenda", UUID.randomUUID().toString());
        dados.put("intencaoVenda", UUID.randomUUID().toString()); dados.put("contaVenda", "CONTA-003"); dados.put("agressor", "COMPRA");
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", UUID.randomUUID().toString()); e.put("tipo", "NEGOCIO"); e.put("versao", 1); e.put("origem", "matching-engine");
        e.put("correlacao", UUID.nameUUIDFromBytes(("admissao:" + intencao).getBytes(StandardCharsets.UTF_8)).toString());
        e.put("ocorridoEm", "2026-10-23T12:00:00Z"); e.put("ticker", ticker); e.put("dataSessao", "2026-10-23");
        e.put("traceparent", null); e.put("dados", dados);
        return e;
    }
}
