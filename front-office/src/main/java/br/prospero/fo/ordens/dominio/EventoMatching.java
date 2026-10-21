package br.prospero.fo.ordens.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Fato de execução externo validado; nenhum tipo Kafka/JSON atravessa a fronteira do domínio. */
public record EventoMatching(UUID id, UUID correlacao, String ticker, LocalDate sessao, String assinatura,
                             Detalhe detalhe, UUID causacao) {
    public EventoMatching(UUID id, UUID correlacao, String ticker, LocalDate sessao, String assinatura, Detalhe detalhe) {
        this(id, correlacao, ticker, sessao, assinatura, detalhe, null);
    }
    public record Alvo(UUID ordem, UUID intencao, String conta) {
    }

    public sealed interface Detalhe permits Aceita, Oferta, Rejeitada, Encerrada, Negocio, StopPendente, StopAcionado {
    }

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
