package br.prospero.mo.cancelamento;

import br.prospero.mo.cancelamento.dominio.MotivoRecusa;
import br.prospero.mo.cancelamento.dominio.RegrasCancelamento;
import br.prospero.mo.cancelamento.dominio.ReservaAlvo;
import br.prospero.mo.cancelamento.dominio.SolicitacaoCancelamento;
import br.prospero.mo.risco.dominio.Decisao;
import br.prospero.mo.risco.dominio.Lado;
import br.prospero.mo.risco.dominio.MotivoRejeicao;
import br.prospero.mo.risco.dominio.ResultadoRisco;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** RF10c2b: recusas locais do MO, na ordem do ADR-013; a primeira regra que falha decide o motivo. */
class RegrasCancelamentoTest {
    private static final UUID ORDEM = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID INTENCAO = UUID.fromString("b0000000-0000-4000-8000-000000000001");
    private static final LocalDate SESSAO = LocalDate.of(2026, 10, 18);
    private static final SolicitacaoCancelamento PEDIDO =
            new SolicitacaoCancelamento(UUID.randomUUID(), ORDEM, INTENCAO, "CONTA-001", "ALFA3", SESSAO);

    private static Decisao decisao(ResultadoRisco resultado, String conta, String ticker) {
        boolean rejeitada = resultado == ResultadoRisco.REJEITADA;
        return new Decisao(INTENCAO, UUID.randomUUID(), conta, ticker, Lado.VENDA, resultado,
                rejeitada ? MotivoRejeicao.SALDO_INSUFICIENTE : null, null, rejeitada ? null : 10L, 1, Instant.now());
    }

    private static ReservaAlvo reserva(UUID ordem, String conta, String ticker, LocalDate sessao, boolean oco, boolean encerrada) {
        return new ReservaAlvo(ordem, conta, ticker, sessao, oco, encerrada);
    }

    private static Optional<MotivoRecusa> avaliar(Decisao decisao, ReservaAlvo reserva) {
        return RegrasCancelamento.motivo(PEDIDO, decisao, Optional.ofNullable(reserva));
    }

    private static Decisao reservada() {
        return decisao(ResultadoRisco.RESERVADA, "CONTA-001", "ALFA3");
    }

    @Test
    void alvoVivoEComprovadoNaoTemMotivoDeRecusa() {
        assertEquals(Optional.empty(), avaliar(reservada(), reserva(ORDEM, "CONTA-001", "ALFA3", SESSAO, false, false)));
    }

    @Test
    void decisaoRejeitadaRecusaPorRiscoMesmoSemReserva() {
        assertEquals(Optional.of(MotivoRecusa.RISCO_REJEITADO), avaliar(decisao(ResultadoRisco.REJEITADA, "CONTA-001", "ALFA3"), null));
    }

    @Test
    void reservaSemAdmissaoComprovadaRecusaComoNaoComprovada() {
        assertEquals(Optional.of(MotivoRecusa.ADMISSAO_NAO_COMPROVADA), avaliar(reservada(), null));
    }

    @Test
    void origemOcoPrecedeDivergenciaDeAlvo() {
        assertEquals(Optional.of(MotivoRecusa.ORIGEM_OCO),
                avaliar(reservada(), reserva(null, "CONTA-001", "ALFA3", SESSAO, true, false)));
    }

    @Test
    void qualquerDivergenciaDeAlvoRecusaComoAlvoDivergente() {
        var outra = UUID.randomUUID();
        for (var reserva : new ReservaAlvo[]{reserva(outra, "CONTA-001", "ALFA3", SESSAO, false, false),
                reserva(ORDEM, "CONTA-002", "ALFA3", SESSAO, false, false),
                reserva(ORDEM, "CONTA-001", "BETA3", SESSAO, false, false),
                reserva(ORDEM, "CONTA-001", "ALFA3", SESSAO.plusDays(1), false, false)}) {
            assertEquals(Optional.of(MotivoRecusa.ALVO_DIVERGENTE), avaliar(reservada(), reserva), reserva.toString());
        }
        assertEquals(Optional.of(MotivoRecusa.ALVO_DIVERGENTE), avaliar(decisao(ResultadoRisco.RESERVADA, "CONTA-002", "ALFA3"),
                reserva(ORDEM, "CONTA-001", "ALFA3", SESSAO, false, false)));
    }

    @Test
    void reservaEncerradaRecusaSemSaldoVivoSomenteDepoisDasDemaisRegras() {
        assertEquals(Optional.of(MotivoRecusa.SEM_SALDO_VIVO),
                avaliar(reservada(), reserva(ORDEM, "CONTA-001", "ALFA3", SESSAO, false, true)));
        assertEquals(Optional.of(MotivoRecusa.ALVO_DIVERGENTE),
                avaliar(reservada(), reserva(UUID.randomUUID(), "CONTA-001", "ALFA3", SESSAO, false, true)));
    }

    @Test
    void conjuntoDeMotivosEFechadoEIgualAoDoContratoDoResultado() {
        assertEquals(java.util.Set.of("RISCO_REJEITADO", "ADMISSAO_NAO_COMPROVADA", "ORIGEM_OCO", "ALVO_DIVERGENTE", "SEM_SALDO_VIVO"),
                java.util.Arrays.stream(MotivoRecusa.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet()));
    }
}
