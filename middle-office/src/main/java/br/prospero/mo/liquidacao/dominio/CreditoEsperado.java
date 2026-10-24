package br.prospero.mo.liquidacao.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Crédito de um lado do negócio, sem data (ADR-016): a mesma forma descreve a expectativa gravada no consumo do negócio
 * e o conteúdo de um evento LIQUIDACAO_EFETIVADA. Valor exato na escala do recurso; a igualdade do record é a regra de
 * "expectativa idêntica".
 */
public record CreditoEsperado(String negocio, LadoLiquidacao lado, String conta, String ticker, BigDecimal valor) {
    private static final BigDecimal QUANTIDADE_MAXIMA = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal CAIXA_MAXIMO = new BigDecimal("9999999999999999.99");

    public CreditoEsperado {
        Objects.requireNonNull(negocio);
        Objects.requireNonNull(lado);
        Objects.requireNonNull(conta);
        Objects.requireNonNull(ticker);
        // Escala fixa por recurso, sem arredondamento: fração não representável é erro, não truncamento.
        valor = Objects.requireNonNull(valor).setScale(lado.recurso().escala(), RoundingMode.UNNECESSARY);
        BigDecimal maximo = lado.recurso() == RecursoCredito.CAIXA ? CAIXA_MAXIMO : QUANTIDADE_MAXIMA;
        if (valor.signum() <= 0 || valor.compareTo(maximo) > 0) {
            throw new IllegalArgumentException("valor");
        }
    }

    /** Comprador espera a quantidade do ativo; vendedor espera preço × quantidade em centavos exatos. */
    public static List<CreditoEsperado> doNegocio(String negocio, String ticker, String contaCompra, String contaVenda,
                                                  BigDecimal preco, long quantidade) {
        BigDecimal q = BigDecimal.valueOf(quantidade);
        return List.of(new CreditoEsperado(negocio, LadoLiquidacao.COMPRADOR, contaCompra, ticker, q),
                new CreditoEsperado(negocio, LadoLiquidacao.VENDEDOR, contaVenda, ticker,
                        preco.multiply(q).setScale(2, RoundingMode.UNNECESSARY)));
    }

    /**
     * Id do evento esperado (contrato BO → MO): UUID de nome, versão 3 (MD5), de
     * {@code "liquidacao:" + negocio + ":" + lado} em UTF-8, como as demais derivações do projeto.
     */
    public static UUID id(String negocio, LadoLiquidacao lado) {
        return UUID.nameUUIDFromBytes(("liquidacao:" + negocio + ":" + lado.name()).getBytes(StandardCharsets.UTF_8));
    }

    public UUID id() {
        return id(negocio, lado);
    }

    public RecursoCredito recurso() {
        return lado.recurso();
    }
}
