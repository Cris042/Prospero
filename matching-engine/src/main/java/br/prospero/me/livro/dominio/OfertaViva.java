package br.prospero.me.livro.dominio;

import java.math.BigDecimal;
import java.util.UUID;

/** Saldo repousado no livro; mutável só dentro do escritor único do ativo. */
final class OfertaViva {
    final UUID ordem;
    final String conta;
    final Lado lado;
    final BigDecimal preco;
    final long sequencia;
    final long quantidade;
    long executada;

    OfertaViva(UUID ordem, String conta, Lado lado, BigDecimal preco, long sequencia, long quantidade, long executada) {
        this.ordem = ordem;
        this.conta = conta;
        this.lado = lado;
        this.preco = preco;
        this.sequencia = sequencia;
        this.quantidade = quantidade;
        this.executada = executada;
    }

    long restante() {
        return quantidade - executada;
    }
}
