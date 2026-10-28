package br.prospero.me.matching;

import br.prospero.me.matching.helper.EnvelopeMatching;
import br.prospero.me.matching.helper.SessaoMatching;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RF10c2b: despacho do CANCELAR_ORDEM, serializador v5 com bytes iguais às fixtures, deduplicação e replay. */
class CancelamentoMatchingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSAO = "2026-10-18";

    private static EnvelopeMatching.Comando cancelar(String nome) throws Exception {
        return EnvelopeMatching.comando(ContratosV5.ler("matching.comandos.v1/ALFA3/cancelar-ordem-" + nome + ".json"), "ALFA3");
    }

    private static byte[] evento(String nome) throws Exception {
        return ContratosV5.ler("matching.eventos.v1/ALFA3/" + nome + ".json");
    }

    private static UUID admissao(UUID intencao) {
        return UUID.nameUUIDFromBytes(("admissao:" + intencao).getBytes(StandardCharsets.UTF_8));
    }

    private static EnvelopeMatching.Comando limit(UUID ordem, UUID intencao, String conta, String lado, int quantidade) {
        String json = "{\"id\":\"" + admissao(intencao) + "\",\"tipo\":\"ORDEM_ADMITIDA\",\"versao\":1,\"origem\":\"middle-office\","
                + "\"correlacao\":\"" + intencao + "\",\"ocorridoEm\":\"2026-10-18T12:00:00Z\",\"conta\":\"" + conta
                + "\",\"traceparent\":null,\"dados\":{\"ordem\":\"" + ordem + "\",\"ticker\":\"ALFA3\",\"lado\":\"" + lado
                + "\",\"tipo\":\"LIMIT\",\"validade\":\"DIA\",\"quantidade\":" + quantidade + ",\"precoLimite\":\"10.00\",\"precoMaximo\":"
                + (lado.equals("COMPRA") ? "\"10.00\"" : "null") + ",\"dataSessao\":\"" + SESSAO + "\"}}";
        return EnvelopeMatching.comando(json.getBytes(StandardCharsets.UTF_8), "ALFA3");
    }

    private static EnvelopeMatching.Comando parOco(UUID tp, UUID intencao, String conta) throws Exception {
        String json = "{\"id\":\"" + admissao(intencao) + "\",\"tipo\":\"ORDEM_ADMITIDA\",\"versao\":3,\"origem\":\"middle-office\","
                + "\"correlacao\":\"" + intencao + "\",\"ocorridoEm\":\"2026-10-18T12:00:00Z\",\"conta\":\"" + conta
                + "\",\"traceparent\":null,\"dados\":{\"ticker\":\"ALFA3\",\"lado\":\"VENDA\",\"dataSessao\":\"" + SESSAO
                + "\",\"precoMaximo\":null,\"pernas\":[{\"papel\":\"TAKE_PROFIT\",\"ordem\":\"" + tp
                + "\",\"tipo\":\"LIMIT\",\"validade\":\"DIA\",\"quantidade\":4,\"precoLimite\":\"10.00\"},"
                + "{\"papel\":\"STOP_LOSS\",\"ordem\":\"" + UUID.nameUUIDFromBytes(("sl:" + tp).getBytes(StandardCharsets.UTF_8))
                + "\",\"tipo\":\"STOP\",\"validade\":\"DIA\",\"quantidade\":4,\"precoLimite\":null,\"precoGatilho\":\"9.00\"}]}}";
        return EnvelopeMatching.comando(json.getBytes(StandardCharsets.UTF_8), "ALFA3");
    }

    /** Venda de 10 do alvo, compra de 4 que a executa: o alvo repousa com 4 executadas e 6 de saldo. */
    private static SessaoMatching comSaldoParcial(EnvelopeMatching.Comando cancelar) {
        var alvo = cancelar.cancelamento();
        var sessao = new SessaoMatching();
        sessao.processar(limit(alvo.ordem(), alvo.intencao(), alvo.conta(), "VENDA", 10));
        var eventos = sessao.processar(limit(UUID.nameUUIDFromBytes("compra".getBytes(StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes("intencao-compra".getBytes(StandardCharsets.UTF_8)), "CONTA-002", "COMPRA", 4));
        assertTrue(eventos.stream().anyMatch(e -> new String(e, StandardCharsets.UTF_8).contains("\"NEGOCIO\"")));
        return sessao;
    }

    @Test
    void retiradaAplicadaEmiteTerminalEDecisaoComOsBytesDasFixtures() throws Exception {
        var comando = cancelar("aplicavel");
        var sessao = comSaldoParcial(comando);
        var saida = sessao.processar(comando);
        assertEquals(2, saida.size());
        assertArrayEquals(evento("ordem-encerrada-cancelada-manual"), saida.get(0));
        assertArrayEquals(evento("cancelamento-decidido-aplicado"), saida.get(1));
    }

    @Test
    void retiradaTiraOSaldoDoLivroSemNegocioPosterior() throws Exception {
        var comando = cancelar("aplicavel");
        var sessao = comSaldoParcial(comando);
        sessao.processar(comando);
        var compra = sessao.processar(limit(UUID.nameUUIDFromBytes("compra2".getBytes(StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes("intencao-compra2".getBytes(StandardCharsets.UTF_8)), "CONTA-002", "COMPRA", 6));
        for (byte[] bruto : compra) assertFalse(new String(bruto, StandardCharsets.UTF_8).contains("\"NEGOCIO\""));
    }

    @Test
    void recusasEmitemSoADecisaoComOsBytesDasFixtures() throws Exception {
        var semSaldo = cancelar("sem-saldo");
        var sessao = new SessaoMatching();
        var saida = sessao.processar(semSaldo);
        assertEquals(1, saida.size());
        assertArrayEquals(evento("cancelamento-decidido-sem-saldo-vivo"), saida.getFirst());
        assertNull(sessao.sessao(), "recusa não abre sessão");

        var divergente = cancelar("alvo-divergente");
        var alvo = divergente.cancelamento();
        var sessaoDivergente = new SessaoMatching();
        sessaoDivergente.processar(limit(alvo.ordem(), alvo.intencao(), "CONTA-004", "VENDA", 10));
        var recusa = sessaoDivergente.processar(divergente);
        assertEquals(1, recusa.size());
        assertArrayEquals(evento("cancelamento-decidido-alvo-divergente"), recusa.getFirst());

        var oco = cancelar("origem-oco");
        var sessaoOco = new SessaoMatching();
        sessaoOco.processar(parOco(oco.cancelamento().ordem(), oco.cancelamento().intencao(), oco.cancelamento().conta()));
        var recusaOco = sessaoOco.processar(oco);
        assertEquals(1, recusaOco.size());
        assertArrayEquals(evento("cancelamento-decidido-origem-oco"), recusaOco.getFirst());
    }

    @Test
    void recusaPorOcoNaoDissolveOGrupoNemTocaNoLivro() throws Exception {
        var oco = cancelar("origem-oco");
        var sessao = new SessaoMatching();
        sessao.processar(parOco(oco.cancelamento().ordem(), oco.cancelamento().intencao(), oco.cancelamento().conta()));
        sessao.processar(oco);
        var compra = sessao.processar(limit(UUID.nameUUIDFromBytes("c".getBytes(StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes("ic".getBytes(StandardCharsets.UTF_8)), "CONTA-002", "COMPRA", 4));
        assertTrue(compra.stream().anyMatch(e -> new String(e, StandardCharsets.UTF_8).contains("\"NEGOCIO\"")),
                "a perna OCO continua viva no livro");
    }

    @Test
    void repeticaoDoMesmoComandoNaoProduzEventoNemEfeito() throws Exception {
        var comando = cancelar("aplicavel");
        var sessao = comSaldoParcial(comando);
        assertEquals(2, sessao.processar(comando).size());
        assertEquals(List.of(), sessao.processar(comando));
        assertEquals(List.of(), sessao.processar(comando));
    }

    @Test
    void mesmoIdComAlvoDivergenteBloqueiaOEscritor() throws Exception {
        var comando = cancelar("sem-saldo");
        var sessao = new SessaoMatching();
        sessao.processar(comando);
        String trocado = new String(ContratosV5.ler("matching.comandos.v1/ALFA3/cancelar-ordem-sem-saldo.json"), StandardCharsets.UTF_8)
                .replace("CONTA-001", "CONTA-002");
        var divergente = EnvelopeMatching.comando(trocado.getBytes(StandardCharsets.UTF_8), "ALFA3");
        assertEquals(comando.id(), divergente.id());
        assertThrows(IllegalArgumentException.class, () -> sessao.processar(divergente));
    }

    @Test
    void replayReconstroiADeduplicacaoEOsMesmosBytes() throws Exception {
        var comando = cancelar("aplicavel");
        var alvo = comando.cancelamento();
        List<EnvelopeMatching.Comando> log = List.of(
                limit(alvo.ordem(), alvo.intencao(), alvo.conta(), "VENDA", 10),
                limit(UUID.nameUUIDFromBytes("compra".getBytes(StandardCharsets.UTF_8)),
                        UUID.nameUUIDFromBytes("intencao-compra".getBytes(StandardCharsets.UTF_8)), "CONTA-002", "COMPRA", 4),
                comando, comando, cancelar("sem-saldo"));
        List<List<byte[]>> primeira = executar(log), segunda = executar(log);
        assertEquals(primeira.size(), segunda.size());
        for (int i = 0; i < primeira.size(); i++) {
            assertEquals(primeira.get(i).size(), segunda.get(i).size());
            for (int j = 0; j < primeira.get(i).size(); j++) assertArrayEquals(primeira.get(i).get(j), segunda.get(i).get(j));
        }
        assertEquals(List.of(), primeira.get(3), "segunda entrega do mesmo comando");
    }

    @Test
    void replayMistoPreservaOsEnvelopesHistoricosV1aV4() throws Exception {
        for (int v = 1; v <= 4; v++) {
            List<EnvelopeMatching.Comando> log = new ArrayList<>();
            try (var in = getClass().getResourceAsStream("/rf10a/historico-v" + v + "-comandos.ndjson")) {
                for (String linha : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                    log.add(EnvelopeMatching.comando(linha.getBytes(StandardCharsets.UTF_8), "ALFA3"));
                }
            }
            int historico = log.size();
            log.add(cancelar("sem-saldo"));
            log.add(cancelar("sem-saldo"));
            List<List<byte[]>> primeira = executar(log), segunda = executar(log);
            String esperado;
            try (var in = getClass().getResourceAsStream("/rf10a/historico-v" + v + "-envelopes.ndjson")) {
                esperado = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            var recebido = new StringBuilder();
            for (int c = 0; c < historico; c++) {
                for (byte[] e : primeira.get(c)) recebido.append(c).append('\t').append(new String(e, StandardCharsets.UTF_8)).append('\n');
            }
            assertEquals(esperado.strip(), recebido.toString().strip(), "bytes v" + v + " inalterados");
            assertEquals(1, primeira.get(historico).size());
            assertEquals(5, JSON.readTree(primeira.get(historico).getFirst()).path("versao").asInt());
            assertEquals(List.of(), primeira.get(historico + 1));
            for (int c = 0; c < primeira.size(); c++) {
                for (int e = 0; e < primeira.get(c).size(); e++) assertArrayEquals(primeira.get(c).get(e), segunda.get(c).get(e));
            }
        }
    }

    @Test
    void checkpointUsaASessaoPersistidaEFixaASessaoDoComandoSoSemLog() throws Exception {
        var comando = cancelar("sem-saldo");
        LocalDate dia = LocalDate.parse(SESSAO);
        assertEquals(dia, SessaoMatching.sessaoDoCheckpoint(comando, null));
        assertEquals(dia, SessaoMatching.sessaoDoCheckpoint(comando, dia));
        var outro = LocalDate.parse("2026-10-17");
        assertEquals(outro, SessaoMatching.sessaoDoCheckpoint(comando, outro), "cancelamento nunca troca a sessão do log");
    }

    private static List<List<byte[]>> executar(List<EnvelopeMatching.Comando> log) {
        var sessao = new SessaoMatching();
        var lotes = new ArrayList<List<byte[]>>();
        for (var comando : log) lotes.add(sessao.processar(comando));
        return lotes;
    }
}
