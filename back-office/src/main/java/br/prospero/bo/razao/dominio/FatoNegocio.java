package br.prospero.bo.razao.dominio;

import br.prospero.bo.posicoes.dominio.EventoMatching;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Fato econômico do negócio persistido com a projeção; valor = preço × quantidade, exato em centavos. */
public record FatoNegocio(String negocio, String ticker, LocalDate sessao, LocalDate liquidacao, BigDecimal preco,
                          long quantidade, EventoMatching.Alvo compra, EventoMatching.Alvo venda) {
    private static final BigDecimal PRECO_MAXIMO = new BigDecimal("1000000.00");

    public FatoNegocio {
        if (negocio == null || !Montante.ATIVOS.contains(ticker) || sessao == null || liquidacao == null
                || !liquidacao.isAfter(sessao) || compra == null || venda == null) {
            throw new IllegalArgumentException("Fato de negócio incompleto.");
        }
        if (preco == null || preco.scale() != 2 || preco.signum() <= 0 || preco.compareTo(PRECO_MAXIMO) > 0
                || quantidade < 1 || quantidade > 1_000_000) {
            throw new IllegalArgumentException("Preço ou quantidade inválidos.");
        }
    }

    /** A data de liquidação parte da dataSessao (America/Sao_Paulo) do envelope, nunca de ocorridoEm. */
    public static FatoNegocio de(EventoMatching evento, EventoMatching.Negocio negocio, Calendario calendario) {
        return new FatoNegocio(negocio.id(), evento.ticker(), evento.sessao(), calendario.liquidacao(evento.sessao()),
                negocio.preco(), negocio.quantidade(), negocio.compra(), negocio.venda());
    }

    public BigDecimal valor() { return preco.multiply(BigDecimal.valueOf(quantidade)); }

    public Montante financeiro() { return Montante.brl(valor()); }

    public Montante ativo() { return Montante.ativo(ticker, quantidade); }
}
