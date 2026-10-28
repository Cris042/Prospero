package br.prospero.fo.ordens;

import br.prospero.fo.comum.ContratosV5;
import br.prospero.fo.comum.dominio.ParticaoBloqueadaException;
import br.prospero.fo.ordens.dominio.EstadoOrdem;
import br.prospero.fo.ordens.dominio.LadoOrdem;
import br.prospero.fo.ordens.dominio.MensagemRisco;
import br.prospero.fo.ordens.dominio.Ordem;
import br.prospero.fo.ordens.dominio.ResultadoCancelamento;
import br.prospero.fo.ordens.dominio.TipoOrdem;
import br.prospero.fo.ordens.dominio.Validade;
import br.prospero.fo.ordens.helper.EnvelopeRisco;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RF10: o FO emite exatamente os bytes da fixture v5 e lê o RESULTADO_CANCELAMENTO do MO. */
class CancelamentoSolicitadoContratoTest {
    private static final UUID ORDEM = UUID.fromString("a0000000-0000-4000-8000-000000000010");
    private static final UUID INTENCAO = UUID.fromString("b0000000-0000-4000-8000-000000000010");

    @Test
    void serializacaoIgualAFixtureCompartilhada() throws Exception {
        byte[] fixture = ContratosV5.ler("risco.comandos.v1/CONTA-002/cancelamento-solicitado.json");
        Ordem ordem = new Ordem(ORDEM, "CONTA-002", "ALFA3", LadoOrdem.VENDA, TipoOrdem.LIMIT, Validade.DIA, 10,
                new java.math.BigDecimal("10.00"), null, null, null, INTENCAO, EstadoOrdem.ABERTA,
                LocalDate.parse("2026-10-18"), Instant.parse("2026-10-18T11:00:00Z"), null, null, 0, 0, null);
        String json = EnvelopeRisco.cancelamentoSolicitado(ordem, Instant.parse("2026-10-18T12:00:00Z"),
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        assertEquals(new String(fixture, StandardCharsets.UTF_8), json);
    }

    @Test
    void leitorAceitaResultadosValidosDaFixtureComoMensagemDeCancelamento() throws Exception {
        for (var caso : ContratosV5.casos("risco.resultados.v1", false)) {
            MensagemRisco lida = EnvelopeRisco.ler(caso.bytes());
            var resultado = assertInstanceOf(ResultadoCancelamento.class, lida, caso.nome());
            assertEquals(caso.nome().contains("confirmado"), resultado.confirmado(), caso.nome());
            assertEquals("CONTA-002", resultado.conta());
            assertEquals(resultado.confirmado(), resultado.motivo() == null);
        }
    }

    @Test
    void corpusNegativoDeTipoReconhecidoBloqueiaAParticao() throws Exception {
        int bloqueados = 0;
        for (var caso : ContratosV5.casos("risco.resultados.v1", true)) {
            try {
                EnvelopeRisco.ler(caso.bytes());
                if (!caso.nome().equals("resultado-conta-diferente-da-chave.json")) {
                    fail("deveria recusar " + caso.nome());
                }
            } catch (ParticaoBloqueadaException esperado) {
                assertEquals("RESULTADO_CANCELAMENTO_INVALIDO", esperado.getMessage(), caso.nome());
                bloqueados++;
            } catch (br.prospero.fo.comum.dominio.MensagemInvalidaException ilegivel) {
                // JSON ilegível ou acima do limite não identifica o tipo: descarte como as demais mensagens inválidas.
            }
        }
        assertTrue(bloqueados >= 10);
    }

    @Test
    void leituraDoFluxoDeRiscoNaoAceitaResultadoDeCancelamento() throws Exception {
        byte[] valido = ContratosV5.casos("risco.resultados.v1", false).getFirst().bytes();
        assertThrows(br.prospero.fo.comum.dominio.MensagemInvalidaException.class, () -> EnvelopeRisco.resultado(valido));
    }
}
