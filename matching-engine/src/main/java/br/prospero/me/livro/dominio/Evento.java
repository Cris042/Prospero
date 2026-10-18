package br.prospero.me.livro.dominio;

import java.math.BigDecimal;
import java.util.UUID;

/** Catálogo fechado de eventos do matching (ADR-013); RF06 deriva profundidade e último preço deles. */
public sealed interface Evento {
    String ticker();

    record OrdemAceita(UUID ordem, String ticker, long sequencia) implements Evento {
    }

    record OrdemRejeitada(UUID ordem, String ticker, MotivoRejeicao motivo) implements Evento {
    }

    /** Negócio ao preço da oferta passiva; id determinístico TICKER-sessão-n. */
    record Negocio(String id, String ticker, BigDecimal preco, long quantidade, UUID ordemCompra, String contaCompra,
                   UUID ordemVenda, String contaVenda, Lado agressor) implements Evento {
    }

    /** Saldo que passou a repousar no livro. */
    record OfertaNoLivro(UUID ordem, String ticker, Lado lado, BigDecimal preco, long restante) implements Evento {
    }

    /** Fim da vida da ordem: executada + cancelada = quantidade original. */
    record OrdemEncerrada(UUID ordem, String ticker, MotivoEncerramento motivo, long executada, long cancelada)
            implements Evento {
    }
}
