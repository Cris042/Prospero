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

    /** Stop aceito aguardando negócio gatilho; não é oferta; integra o contrato Kafka v2. */
    record StopPendente(UUID ordem, String ticker, BigDecimal precoGatilho) implements Evento {
    }

    /** Conversão interna com nova prioridade, preservando a identidade da ordem. */
    record StopAcionado(UUID ordem, String ticker, TipoOrdem tipoExecutavel, long sequencia) implements Evento {
    }

    /** Negócio ao preço da oferta passiva; id determinístico TICKER-sessão-n. */
    record Negocio(String id, String ticker, BigDecimal preco, long quantidade, UUID ordemCompra, String contaCompra,
                   UUID ordemVenda, String contaVenda, Lado agressor) implements Evento {
    }

    /** Perna OCO pendente reduzida pela execução da irmã; restante é a nova quantidade da perna (RF05c.3a). */
    record OrdemReduzida(UUID ordem, String ticker, long reduzida, long restante) implements Evento {
    }

    /** Saldo que passou a repousar no livro. */
    record OfertaNoLivro(UUID ordem, String ticker, Lado lado, BigDecimal preco, long restante) implements Evento {
    }

    /** Fim da vida da ordem: executada + reduções OCO + cancelada = quantidade original. */
    record OrdemEncerrada(UUID ordem, String ticker, MotivoEncerramento motivo, long executada, long cancelada)
            implements Evento {
    }
}
