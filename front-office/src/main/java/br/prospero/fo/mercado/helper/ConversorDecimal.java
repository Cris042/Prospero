package br.prospero.fo.mercado.helper;

import br.prospero.fo.mercado.dominio.RegraMercadoException;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Converte decimais textuais com gramática e limites verificados antes da conversão. */
public final class ConversorDecimal {
    private static final Pattern PRECO = Pattern.compile("[0-9]{1,7}(\\.[0-9]{1,2})?");
    private static final BigDecimal MAXIMO = new BigDecimal("1000000.00");

    private ConversorDecimal() {
    }

    public static BigDecimal preco(String texto, BigDecimal tick) {
        if (texto == null || !PRECO.matcher(texto).matches()) {
            throw new RegraMercadoException("PRECO_INVALIDO", "Preço deve ser decimal textual positivo com até duas casas.");
        }
        BigDecimal valor = new BigDecimal(texto).setScale(2);
        if (valor.signum() <= 0 || valor.compareTo(MAXIMO) > 0) {
            throw new RegraMercadoException("PRECO_INVALIDO", "Preço fora dos limites aceitos.");
        }
        if (valor.remainder(tick).signum() != 0) {
            throw new RegraMercadoException("PRECO_FORA_DO_TICK", "Preço não é múltiplo do tick do ativo.");
        }
        return valor;
    }

    public static String texto(BigDecimal valor) {
        return valor == null ? null : valor.toPlainString();
    }
}
