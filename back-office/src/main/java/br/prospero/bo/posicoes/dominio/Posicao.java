package br.prospero.bo.posicoes.dominio;

import java.math.BigDecimal;

/** Posição líquida dos negócios desde a origem do log, distinta da custódia no risco. */
public record Posicao(long quantidade, Racional medio, Racional realizado) {
    public Posicao {
        if (quantidade == Long.MIN_VALUE || realizado == null || (quantidade == 0) != (medio == null)
                || medio != null && medio.numerador().signum() <= 0) {
            throw new IllegalArgumentException("Posição incoerente.");
        }
    }

    public static Posicao vazia() { return new Posicao(0, null, Racional.ZERO); }

    public Posicao aplicar(long delta, BigDecimal preco) {
        validarPreco(preco);
        if (delta == 0 || delta == Long.MIN_VALUE) throw new IllegalArgumentException("Quantidade inválida.");
        long nova = Math.addExact(quantidade, delta);
        if (nova == Long.MIN_VALUE) throw new ArithmeticException("Quantidade excedida.");
        Racional executado = Racional.de(preco);
        if (quantidade == 0) return new Posicao(nova, executado, realizado);
        if (Long.signum(quantidade) == Long.signum(delta)) {
            Racional custo = medio.multiplicar(Math.abs(quantidade)).somar(executado.multiplicar(Math.abs(delta)));
            return new Posicao(nova, custo.dividir(Math.abs(nova)), realizado);
        }
        long encerrada = Math.min(Math.abs(quantidade), Math.abs(delta));
        Racional resultado = executado.subtrair(medio).multiplicar(encerrada).multiplicar(Long.signum(quantidade));
        Racional novoMedio = medioAposReducao(nova, executado);
        return new Posicao(nova, novoMedio, realizado.somar(resultado));
    }

    private Racional medioAposReducao(long nova, Racional executado) {
        if (nova == 0) return null;
        if (Long.signum(nova) == Long.signum(quantidade)) return medio;
        return executado;
    }

    public Racional naoRealizado(BigDecimal cotacao) {
        if (cotacao == null) return null;
        validarPreco(cotacao);
        if (quantidade == 0) return Racional.ZERO;
        return Racional.de(cotacao).subtrair(medio).multiplicar(quantidade);
    }

    private static void validarPreco(BigDecimal preco) {
        if (preco == null || preco.scale() > 2 || preco.signum() <= 0
                || preco.compareTo(new BigDecimal("1000000.00")) > 0) {
            throw new IllegalArgumentException("Preço inválido.");
        }
    }
}
