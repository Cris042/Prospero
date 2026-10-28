package br.prospero.fo.ordens.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Fato de execução externo validado; nenhum tipo Kafka/JSON atravessa a fronteira do domínio. */
public record EventoMatching(UUID id, UUID correlacao, String ticker, LocalDate sessao, String assinatura,
                             Detalhe detalhe, Contexto contexto) {
    /** Metadados externos podem estar ausentes nos fatos legados. */
    public record Contexto(UUID causacao, int versao, java.time.Instant ocorridoEm, String traceparent) { }

    public EventoMatching(UUID id, UUID correlacao, String ticker, LocalDate sessao, String assinatura, Detalhe detalhe) {
        this(id, correlacao, ticker, sessao, assinatura, detalhe, new Contexto(null, 1, null, null));
    }

    public EventoMatching(UUID id, UUID correlacao, String ticker, LocalDate sessao, String assinatura, Detalhe detalhe,
                          UUID causacao) {
        this(id, correlacao, ticker, sessao, assinatura, detalhe, new Contexto(causacao, causacao == null ? 1 : 2, null, null));
    }

    public UUID causacao() { return contexto.causacao(); }
    public int versao() { return contexto.versao(); }
    public java.time.Instant ocorridoEm() { return contexto.ocorridoEm(); }
    public String traceparent() { return contexto.traceparent(); }

    public record Alvo(UUID ordem, UUID intencao, String conta) {
    }

    public sealed interface Detalhe permits Aceita, Oferta, Rejeitada, Encerrada, Negocio, StopPendente, StopAcionado,
            Reduzida, SessaoEncerrada, CancelamentoDecidido {
    }

    /** Resultado do ME para o comando de cancelamento (v5); recusa não é encerramento nem rejeição de admissão. */
    public enum ResultadoCancelamento { APLICADO, SEM_SALDO_VIVO, ALVO_DIVERGENTE, ORIGEM_OCO }

    /** Último evento do comando de cancelamento; {@code terminal} é o id do ORDEM_ENCERRADA, só em APLICADO. */
    public record CancelamentoDecidido(Alvo alvo, ResultadoCancelamento resultado, UUID terminal) implements Detalhe { }

    /** Causação determinística do comando de cancelamento da ordem (ADR-013 RF10c2). */
    public static UUID comandoCancelamento(UUID ordem) {
        return UUID.nameUUIDFromBytes(("cancelamento:" + ordem).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Marco v4 do encerramento de um ativo/data (RF05c.4); não altera ordens. */
    public record SessaoEncerrada(long expiradas) implements Detalhe { }

    /** Causação determinística do encerramento (ADR-013 RF05c.4). */
    public static UUID encerramento(String ticker, LocalDate sessao) {
        return UUID.nameUUIDFromBytes(("encerramento:" + ticker + ":" + sessao).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Stop Loss reduzido pela execução do Take Profit (v3); restante é a nova quantidade vigente. */
    public record Reduzida(Alvo alvo, long reduzida, long restante) implements Detalhe { }

    public record Aceita(Alvo alvo, long sequencia) implements Detalhe {
    }

    public record StopPendente(Alvo alvo, BigDecimal precoGatilho) implements Detalhe { }

    public record StopAcionado(Alvo alvo, TipoOrdem tipoExecutavel, long sequencia) implements Detalhe { }

    public record Oferta(Alvo alvo, LadoOrdem lado, BigDecimal preco, long restante) implements Detalhe {
    }

    public record Rejeitada(Alvo alvo, MotivoRejeicao motivo) implements Detalhe {
    }

    public record Encerrada(Alvo alvo, String motivo, long executada, long cancelada) implements Detalhe {
    }

    public record Negocio(String id, BigDecimal preco, long quantidade, Alvo compra, Alvo venda,
                          LadoOrdem agressor) implements Detalhe {
    }
}
