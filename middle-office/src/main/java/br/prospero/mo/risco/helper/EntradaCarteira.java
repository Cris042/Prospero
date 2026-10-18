package br.prospero.mo.risco.helper;

import br.prospero.mo.comum.dominio.Categoria;
import br.prospero.mo.comum.dominio.RegraNegocioException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Validação da preparação de carteira: saldo decimal exato e custódia inteira por ativo conhecido. */
public final class EntradaCarteira {
    private static final Pattern SALDO = Pattern.compile("\\d{1,10}(\\.\\d{1,2})?");
    private static final long CUSTODIA_MAXIMA = 10_000_000;

    private EntradaCarteira() {
    }

    public static BigDecimal saldo(String texto) {
        if (texto == null || !SALDO.matcher(texto).matches()) {
            throw new RegraNegocioException(Categoria.ENTRADA_INVALIDA, "SALDO_INVALIDO",
                    "Saldo deve ser decimal não negativo com até duas casas e até 9.999.999.999,99.");
        }
        return new BigDecimal(texto).setScale(2);
    }

    public static Map<String, Long> custodia(Map<String, Long> custodia, Predicate<String> ativoConhecido) {
        if (custodia == null) {
            return Map.of();
        }
        custodia.forEach((ticker, quantidade) -> {
            if (ticker == null || !ativoConhecido.test(ticker)) {
                throw new RegraNegocioException(Categoria.REGRA_VIOLADA, "ATIVO_DESCONHECIDO", "Ativo não cadastrado.");
            }
            if (quantidade == null || quantidade < 0 || quantidade > CUSTODIA_MAXIMA) {
                throw new RegraNegocioException(Categoria.ENTRADA_INVALIDA, "CUSTODIA_INVALIDA",
                        "Custódia deve ser inteira entre 0 e 10.000.000.");
            }
        });
        return Map.copyOf(custodia);
    }
}
