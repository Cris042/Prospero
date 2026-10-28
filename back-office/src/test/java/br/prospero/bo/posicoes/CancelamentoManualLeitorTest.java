package br.prospero.bo.posicoes;

import br.prospero.bo.comum.ContratosV5;
import br.prospero.bo.posicoes.dominio.EventoMatching;
import br.prospero.bo.posicoes.dominio.MensagemInvalidaException;
import br.prospero.bo.posicoes.dominio.ProjecaoIncoerenteException;
import br.prospero.bo.posicoes.helper.CoerenciaMatching;
import br.prospero.bo.posicoes.helper.EnvelopeMatching;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RF10c2a: o BO valida a coerência do envelope v5 contra as fixtures compartilhadas e não o interpreta. */
class CancelamentoManualLeitorTest {
    private static final String TOPICO = "matching.eventos.v1";

    private static EventoMatching fixture(String nome) throws Exception {
        return EnvelopeMatching.evento(ContratosV5.ler(TOPICO + "/ALFA3/" + nome + ".json"), "ALFA3");
    }

    @Test
    void fixturesValidasSaoLidasECoerentes() throws Exception {
        var terminal = fixture("ordem-encerrada-cancelada-manual");
        assertEquals(5, terminal.versao());
        var f = assertInstanceOf(EventoMatching.Encerrada.class, terminal.detalhe());
        assertEquals("CANCELADA_MANUAL", f.motivo());
        assertEquals(6, f.cancelada());
        CoerenciaMatching.validar(terminal);
        for (String nome : List.of("aplicado", "sem-saldo-vivo", "alvo-divergente", "origem-oco")) {
            var decisao = fixture("cancelamento-decidido-" + nome);
            assertInstanceOf(EventoMatching.CancelamentoDecidido.class, decisao.detalhe(), nome);
            CoerenciaMatching.validar(decisao);
        }
    }

    @Test
    void correlacaoAdulteradaDepoisDaLeituraEhIncoerente() throws Exception {
        var e = fixture("cancelamento-decidido-aplicado");
        var adulterado = new EventoMatching(e.id(), UUID.randomUUID(), e.ticker(), e.sessao(), e.assinatura(), e.detalhe(), e.contexto());
        assertThrows(ProjecaoIncoerenteException.class, () -> CoerenciaMatching.validar(adulterado));
    }

    @Test
    void corpusNegativoFalhaFechadoSemVazarOPayload() throws Exception {
        var invalidos = ContratosV5.casos(TOPICO, true);
        assertTrue(invalidos.size() >= 40, "corpus negativo de eventos v5");
        for (var caso : invalidos) {
            var erro = assertThrows(MensagemInvalidaException.class, () -> EnvelopeMatching.evento(caso.bytes(), caso.chave()), caso.nome());
            assertFalse(erro.getMessage().contains("forjado") || erro.getMessage().contains("\n"), caso.nome());
        }
    }

    @Test
    void tickerDiferenteDaChaveKafkaEhInvalido() throws Exception {
        var bytes = ContratosV5.ler(TOPICO + "/ALFA3/ordem-encerrada-cancelada-manual.json");
        assertThrows(MensagemInvalidaException.class, () -> EnvelopeMatching.evento(bytes, "BETA3"));
    }

    @Test
    void replayMistoDeEnvelopesHistoricosV1aV4ComV5() throws Exception {
        int historicos = 0;
        for (int v = 1; v <= 4; v++) {
            var arquivo = Path.of("..", "matching-engine", "src", "test", "resources", "rf10a", "historico-v" + v + "-envelopes.ndjson");
            for (String linha : Files.readAllLines(arquivo, StandardCharsets.UTF_8)) {
                var e = EnvelopeMatching.evento(linha.substring(linha.indexOf('\t') + 1).getBytes(StandardCharsets.UTF_8), "ALFA3");
                assertTrue(e.versao() <= v, "v" + v);
                CoerenciaMatching.validar(e);
                historicos++;
            }
        }
        assertTrue(historicos > 50);
    }

    /** O BO só lê: nenhum fonte de produção escreve os tipos de cancelamento nem os motivos novos. */
    @Test
    void nenhumCaminhoDeProducaoDoBoSerializaOsTiposDeCancelamento() throws Exception {
        try (var fontes = Files.walk(Path.of("src", "main", "java"))) {
            for (Path fonte : fontes.filter(p -> p.toString().endsWith(".java")).toList()) {
                String texto = Files.readString(fonte);
                for (String tipo : List.of("CANCELAR_ORDEM", "CANCELAMENTO_SOLICITADO", "RESULTADO_CANCELAMENTO")) {
                    assertFalse(texto.contains(tipo), fonte + " menciona " + tipo);
                }
                if (texto.contains("CANCELAMENTO_DECIDIDO") || texto.contains("CANCELADA_MANUAL")) {
                    assertTrue(fonte.endsWith(Path.of("helper", "EnvelopeMatching.java")), fonte.toString());
                    assertFalse(texto.contains("put(\"tipo\", \"CANCELAMENTO_DECIDIDO\")"));
                }
            }
        }
    }
}
