package br.prospero.bo.razao.dominio;

import java.math.BigDecimal;
import java.util.Set;

/** Quantia por unidade: BRL com escala exata de centavos; ativo em quantidade inteira. Nunca double. */
public record Montante(String unidade, BigDecimal valor) {
    public static final String BRL = "BRL";
    public static final Set<String> ATIVOS = Set.of("ALFA3", "BETA3", "GAMA3", "DELTA3", "OMEGA3");
    /** Cabe em NUMERIC(15,2): preço máximo 1.000.000,00 × quantidade máxima 1.000.000. */
    private static final BigDecimal MAXIMO_BRL = new BigDecimal("9999999999999.99");

    public Montante {
        if (unidade == null || valor == null || valor.signum() <= 0) throw new IllegalArgumentException("Montante inválido.");
        if (BRL.equals(unidade)) {
            if (valor.scale() != 2 || valor.compareTo(MAXIMO_BRL) > 0) throw new IllegalArgumentException("Montante BRL inválido.");
        } else if (!ATIVOS.contains(unidade) || valor.scale() != 0 || bitLength(valor) >= Long.SIZE) {
            throw new IllegalArgumentException("Quantidade inválida.");
        }
    }

    private static int bitLength(BigDecimal valor) { return valor.unscaledValue().bitLength(); }

    public static Montante brl(BigDecimal valor) { return new Montante(BRL, valor); }

    public static Montante ativo(String ticker, long quantidade) { return new Montante(ticker, BigDecimal.valueOf(quantidade)); }

    public boolean financeiro() { return BRL.equals(unidade); }

    public String texto() { return valor.toPlainString(); }
}
