package br.prospero.mo.cancelamento;

import br.prospero.mo.cancelamento.dominio.EstadoCancelamento;
import br.prospero.mo.cancelamento.dominio.MotivoRecusa;
import br.prospero.mo.cancelamento.dominio.OperacaoCancelamento;
import br.prospero.mo.cancelamento.dominio.SolicitacaoCancelamento;
import br.prospero.mo.cancelamento.helper.EnvelopeCancelamento;
import br.prospero.mo.comum.ContratosV5;
import br.prospero.mo.risco.helper.EnvelopeRisco;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RF10c2b: os bytes que o MO escreve são exatamente os das fixtures compartilhadas com FO e ME. */
class EnvelopeCancelamentoTest {
    private static final Instant AGORA = Instant.parse("2026-10-18T12:00:00Z");
    private static final String TRACE = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    private static UUID derivado(String semente) {
        return UUID.nameUUIDFromBytes(semente.getBytes(StandardCharsets.UTF_8));
    }

    private static OperacaoCancelamento operacao(String ordem, String intencao, String conta, EstadoCancelamento estado,
                                                 MotivoRecusa motivo) {
        UUID o = UUID.fromString(ordem), i = UUID.fromString(intencao);
        return new OperacaoCancelamento(o, i, derivado("admissao:" + i), conta, "ALFA3", LocalDate.of(2026, 10, 18),
                derivado("cancelamento-solicitado:" + o), derivado("cancelamento:" + o), "0".repeat(64), estado,
                estado == EstadoCancelamento.CONFIRMADO ? UUID.randomUUID() : null,
                estado == EstadoCancelamento.CONFIRMADO ? "APLICADO" : null, motivo, TRACE);
    }

    private static String fixture(String relativo) throws Exception {
        return new String(ContratosV5.ler(relativo), StandardCharsets.UTF_8);
    }

    @Test
    void cancelarOrdemIdenticoAFixtureDoMatching() throws Exception {
        var op = operacao("a0000000-0000-4000-8000-000000000001", "b0000000-0000-4000-8000-000000000001", "CONTA-001",
                EstadoCancelamento.COMANDO_EMITIDO, null);
        assertEquals(fixture("matching.comandos.v1/ALFA3/cancelar-ordem-aplicavel.json"), EnvelopeCancelamento.cancelarOrdem(op, AGORA));
    }

    @Test
    void resultadoConfirmadoIdenticoAFixtureDoRisco() throws Exception {
        var op = operacao("a0000000-0000-4000-8000-000000000010", "b0000000-0000-4000-8000-000000000010", "CONTA-002",
                EstadoCancelamento.CONFIRMADO, null);
        assertEquals(fixture("risco.resultados.v1/CONTA-002/resultado-cancelamento-confirmado.json"), EnvelopeCancelamento.resultado(op, AGORA));
    }

    @Test
    void resultadoRecusadoIdenticoAFixtureDoRisco() throws Exception {
        var op = operacao("a0000000-0000-4000-8000-000000000010", "b0000000-0000-4000-8000-000000000010", "CONTA-002",
                EstadoCancelamento.RECUSADO, MotivoRecusa.SEM_SALDO_VIVO);
        assertEquals(fixture("risco.resultados.v1/CONTA-002/resultado-cancelamento-recusado.json"), EnvelopeCancelamento.resultado(op, AGORA));
    }

    @Test
    void resultadoSoExisteParaEstadosDecididos() {
        var op = operacao("a0000000-0000-4000-8000-000000000010", "b0000000-0000-4000-8000-000000000010", "CONTA-002",
                EstadoCancelamento.COMANDO_EMITIDO, null);
        assertThrows(IllegalStateException.class, () -> EnvelopeCancelamento.resultado(op, AGORA));
    }

    @Test
    void solicitacaoDaFixtureTemOsAlvosEAssinaturaEstavel() throws Exception {
        byte[] bytes = ContratosV5.ler("risco.comandos.v1/CONTA-002/cancelamento-solicitado.json");
        SolicitacaoCancelamento s = EnvelopeRisco.solicitacao(bytes).orElseThrow();
        assertEquals(UUID.fromString("a0000000-0000-4000-8000-000000000010"), s.ordem());
        assertEquals(UUID.fromString("b0000000-0000-4000-8000-000000000010"), s.intencao());
        assertEquals("CONTA-002", s.conta());
        assertEquals("ALFA3", s.ticker());
        assertEquals(LocalDate.of(2026, 10, 18), s.dataSessao());
        assertEquals(UUID.fromString("d9f4a497-ff54-306b-98b7-89c8c1490209"), s.id());
        assertEquals(EnvelopeCancelamento.assinatura(s), EnvelopeCancelamento.assinatura(EnvelopeRisco.solicitacao(bytes).orElseThrow()));
        assertTrue(EnvelopeCancelamento.assinatura(s).matches("[0-9a-f]{64}"));
        var outra = new SolicitacaoCancelamento(s.id(), s.ordem(), s.intencao(), s.conta(), "BETA3", s.dataSessao());
        assertNotEquals(EnvelopeCancelamento.assinatura(s), EnvelopeCancelamento.assinatura(outra));
    }

    @Test
    void naoSolicitacaoDevolveVazioESolicitacaoInvalidaBloqueia() throws Exception {
        byte[] avaliar = ("{\"id\":\"8c6b6a0e-3b78-4a52-a3a2-0b3e1d4f9c11\",\"tipo\":\"AVALIAR_RISCO\"}").getBytes(StandardCharsets.UTF_8);
        assertTrue(EnvelopeRisco.solicitacao(avaliar).isEmpty());
        for (var caso : ContratosV5.casos("risco.comandos.v1", true)) {
            if (caso.nome().equals("solicitado-maior-que-16-kib.json") || caso.nome().equals("solicitado-chave-duplicada.json")) {
                assertThrows(br.prospero.mo.mensageria.dominio.MensagemInvalidaException.class, () -> EnvelopeRisco.solicitacao(caso.bytes()), caso.nome());
            } else if (caso.nome().equals("solicitado-conta-diferente-da-chave.json")) {
                assertTrue(EnvelopeRisco.solicitacao(caso.bytes()).isPresent(), caso.nome());
            } else {
                var erro = assertThrows(br.prospero.mo.mensageria.dominio.ParticaoBloqueadaException.class, () -> EnvelopeRisco.solicitacao(caso.bytes()), caso.nome());
                assertEquals("CANCELAMENTO_SOLICITADO_INVALIDO", erro.getMessage(), caso.nome());
            }
        }
    }
}
