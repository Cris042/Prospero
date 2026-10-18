package br.prospero.fo.ordens.helper;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Gramática de preço e limites de quantidade verificados antes de qualquer conversão. */
public final class EntradaOrdem {
    private static final Pattern PRECO = Pattern.compile("[0-9]{1,7}(\\.[0-9]{1,2})?");
    private static final BigDecimal MAXIMO = new BigDecimal("1000000.00");
    public static final long MAX_QUANTIDADE = 1_000_000L;

    private EntradaOrdem() {
    }

    /** null permanece null (campo não informado); presença indevida é decidida pela política. */
    public static BigDecimal preco(String texto) {
        if (texto == null) {
            return null;
        }
        if (!PRECO.matcher(texto).matches()) {
            throw PoliticaOrdem.regra("PRECO_INVALIDO", "Preço deve ser decimal textual positivo com até duas casas.");
        }
        BigDecimal valor = new BigDecimal(texto).setScale(2);
        if (valor.signum() <= 0 || valor.compareTo(MAXIMO) > 0) {
            throw PoliticaOrdem.regra("PRECO_INVALIDO", "Preço fora dos limites aceitos.");
        }
        return valor;
    }

    public static long quantidade(long quantidade) {
        if (quantidade <= 0 || quantidade > MAX_QUANTIDADE) {
            throw PoliticaOrdem.regra("QUANTIDADE_INVALIDA", "Quantidade deve ser inteira, positiva e até 1000000.");
        }
        return quantidade;
    }
}
