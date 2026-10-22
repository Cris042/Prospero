package br.prospero.fo.ordens;

import br.prospero.fo.comum.dominio.MensagemInvalidaException;
import br.prospero.fo.ordens.dominio.*;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import br.prospero.fo.ordens.helper.ProjetarMatching;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RF05c.4a: expiração DIA v4 e rejeição de admissão tardia projetadas no FO. */
class EncerramentoProjecaoTest {
    private static final UUID ID = UUID.fromString("80ef3249-7815-4560-a260-63ecf28bd581");
    private static final UUID ENCERRAMENTO = UUID.nameUUIDFromBytes("encerramento:ALFA3:2026-10-18".getBytes(StandardCharsets.UTF_8));

    private static EventoMatching evento(int versao, String tipo, UUID causacao, String campos) {
        String raiz = versao == 1 ? "" : "\"causacao\":\"" + causacao + "\",";
        String s = """
                {"id":"%s","tipo":"%s","versao":%d,"origem":"matching-engine","correlacao":"e4b8a895-e56a-4430-925b-29236393db61",%s
                "ocorridoEm":"2026-10-18T21:00:00Z","ticker":"ALFA3","dataSessao":"2026-10-18","traceparent":null,
                "dados":{"ordem":"%s","intencao":"%s","conta":"CONTA-002",%s}}
                """.formatted(UUID.randomUUID(), tipo, versao, raiz, ID, ID, campos);
        return EnvelopeMatching.evento(s.getBytes(StandardCharsets.UTF_8), "ALFA3");
    }

    private static Ordem ordem(TipoOrdem tipo, EstadoOrdem estado, long executada, PapelOco papel, long reduzida) {
        return new Ordem(ID, "CONTA-002", "ALFA3", LadoOrdem.COMPRA, tipo, Validade.DIA, 10,
                tipo == TipoOrdem.STOP ? null : new BigDecimal("10.00"), tipo == TipoOrdem.STOP ? new BigDecimal("10.50") : null,
                papel == null ? null : ID, papel, ID, estado, LocalDate.of(2026, 10, 18), Instant.parse("2026-10-18T12:00:00Z"),
                null, null, executada, 0, null, reduzida);
    }

    private static Ordem aplicar(Ordem o, EventoMatching e) {
        return ProjetarMatching.aplicar(o, e, new EventoMatching.Alvo(ID, ID, "CONTA-002"), new BigDecimal("0.01"), 1);
    }

    @Test
    void expiracaoCancelaSaldoDeLimitParcialEDeStopPendente() {
        Ordem limit = aplicar(ordem(TipoOrdem.LIMIT, EstadoOrdem.PARCIALMENTE_EXECUTADA, 4, null, 0),
                evento(4, "ORDEM_ENCERRADA", ENCERRAMENTO, "\"motivo\":\"EXPIRADA_DIA\",\"executada\":4,\"cancelada\":6"));
        assertEquals(EstadoOrdem.CANCELADA, limit.estado());
        assertEquals("EXPIRADA_DIA", limit.motivoEncerramento());
        assertEquals(6, limit.quantidadeCancelada());
        Ordem stop = aplicar(ordem(TipoOrdem.STOP, EstadoOrdem.AGUARDANDO_GATILHO, 0, PapelOco.STOP_LOSS, 4),
                evento(4, "ORDEM_ENCERRADA", ENCERRAMENTO, "\"motivo\":\"EXPIRADA_DIA\",\"executada\":0,\"cancelada\":6"));
        assertEquals(EstadoOrdem.CANCELADA, stop.estado());
        assertEquals(6, stop.quantidadeCancelada());
    }

    @Test
    void v4ExigeCausacaoDoEncerramentoESoTransportaExpiracao() {
        Ordem aberta = ordem(TipoOrdem.LIMIT, EstadoOrdem.ABERTA, 0, null, 0);
        assertThrows(MatchingIncoerenteException.class, () -> aplicar(aberta,
                evento(4, "ORDEM_ENCERRADA", UUID.randomUUID(), "\"motivo\":\"EXPIRADA_DIA\",\"executada\":0,\"cancelada\":10")));
        assertThrows(MensagemInvalidaException.class, () ->
                evento(4, "ORDEM_ENCERRADA", ENCERRAMENTO, "\"motivo\":\"EXECUTADA\",\"executada\":10,\"cancelada\":0"));
        assertThrows(MensagemInvalidaException.class, () ->
                evento(2, "ORDEM_ENCERRADA", ENCERRAMENTO, "\"motivo\":\"EXPIRADA_DIA\",\"executada\":0,\"cancelada\":10"));
        assertThrows(MensagemInvalidaException.class, () -> evento(4, "ORDEM_ACEITA", ENCERRAMENTO, "\"sequencia\":1"));
    }

    @Test
    void admissaoTardiaRejeitadaPorSessao() {
        for (String motivo : new String[]{"SESSAO_ENCERRADA", "SESSAO_NAO_ENCERRADA"}) {
            Ordem o = aplicar(ordem(TipoOrdem.LIMIT, EstadoOrdem.RESERVADA, 0, null, 0),
                    evento(1, "ORDEM_REJEITADA", null, "\"motivo\":\"" + motivo + "\""));
            assertEquals(EstadoOrdem.REJEITADA, o.estado());
            assertEquals(MotivoRejeicao.valueOf(motivo), o.motivoRejeicao());
        }
    }
}
