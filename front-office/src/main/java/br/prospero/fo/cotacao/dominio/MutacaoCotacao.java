package br.prospero.fo.cotacao.dominio;

import java.math.BigDecimal;
import java.util.UUID;

/** Efeito de um evento do matching sobre o livro e os acumuladores da sessão (ADR-014). */
public sealed interface MutacaoCotacao {
    /** Ordem em repouso no livro com o restante informado pelo ME. */
    record Repousar(UUID ordem, String lado, BigDecimal preco, long restante) implements MutacaoCotacao { }

    /** Execução da ordem passiva; ao zerar, sai do livro. */
    record Consumir(UUID ordem, long quantidade) implements MutacaoCotacao { }

    record Retirar(UUID ordem) implements MutacaoCotacao { }

    /** Nova quantidade vigente, se a ordem estiver no livro. */
    record Ajustar(UUID ordem, long restante) implements MutacaoCotacao { }

    /** Último preço e volumes da sessão. */
    record Negociar(BigDecimal preco, long quantidade) implements MutacaoCotacao { }

    /** Marco do encerramento: livro do ativo/data vazio, indicadores congelados. */
    record Encerrar() implements MutacaoCotacao { }
}
