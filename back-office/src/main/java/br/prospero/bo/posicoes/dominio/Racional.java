package br.prospero.bo.posicoes.dominio;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Representação exata usada pelo cálculo de posições. */
public record Racional(BigInteger numerador, BigInteger denominador) {
    public static final int LIMITE_BITS = 131072;
    public static final Racional ZERO = new Racional(BigInteger.ZERO, BigInteger.ONE);

    public Racional {
        if (numerador == null || denominador == null || denominador.signum() == 0) {
            throw new IllegalArgumentException("Fração inválida.");
        }
        // Produtos e soma podem dobrar a representação antes da redução por MDC.
        orcamento(numerador, 2 * LIMITE_BITS + 2);
        orcamento(denominador, 2 * LIMITE_BITS + 2);
        BigInteger mdc = numerador.gcd(denominador);
        numerador = numerador.divide(mdc);
        denominador = denominador.divide(mdc);
        if (denominador.signum() < 0) {
            numerador = numerador.negate();
            denominador = denominador.negate();
        }
        orcamento(numerador, LIMITE_BITS);
        orcamento(denominador, LIMITE_BITS);
    }

    public static Racional fracao(BigInteger numerador, BigInteger denominador) {
        return new Racional(numerador, denominador);
    }

    public static Racional de(BigDecimal valor) {
        if (valor == null) throw new IllegalArgumentException("Decimal ausente.");
        if (Math.abs((long) valor.scale()) > 39456) throw new IllegalStateException("ORCAMENTO_RACIONAL");
        BigInteger n = valor.unscaledValue();
        orcamento(n, 2 * LIMITE_BITS + 2);
        if (valor.scale() < 0) return fracao(n.multiply(BigInteger.TEN.pow(-valor.scale())), BigInteger.ONE);
        return fracao(n, BigInteger.TEN.pow(valor.scale()));
    }

    public Racional somar(Racional outro) {
        BigInteger mdc = denominador.gcd(outro.denominador);
        BigInteger a = denominador.divide(mdc);
        BigInteger b = outro.denominador.divide(mdc);
        return fracao(numerador.multiply(b).add(outro.numerador.multiply(a)), a.multiply(outro.denominador));
    }

    public Racional subtrair(Racional outro) {
        return somar(fracao(outro.numerador.negate(), outro.denominador));
    }

    public Racional multiplicar(Racional outro) {
        BigInteger a = numerador.gcd(outro.denominador);
        BigInteger b = outro.numerador.gcd(denominador);
        return fracao(numerador.divide(a).multiply(outro.numerador.divide(b)),
                denominador.divide(b).multiply(outro.denominador.divide(a)));
    }

    public Racional multiplicar(long fator) {
        return multiplicar(fracao(BigInteger.valueOf(fator), BigInteger.ONE));
    }
    public Racional dividir(long divisor) {
        if (divisor == 0) throw new IllegalArgumentException("Divisão por zero.");
        return fracao(numerador, denominador.multiply(BigInteger.valueOf(divisor)));
    }

    public String formatar() {
        return new BigDecimal(numerador).divide(new BigDecimal(denominador), 4, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static void orcamento(BigInteger valor, int limite) {
        if (valor.abs().bitLength() > limite) throw new IllegalStateException("ORCAMENTO_RACIONAL");
    }
}
