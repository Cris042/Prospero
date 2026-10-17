package br.prospero.fo.mercado.helper;

import br.prospero.fo.mercado.dominio.Ativo;
import br.prospero.fo.mercado.dominio.Lado;
import br.prospero.fo.mercado.dominio.Negocio;
import br.prospero.fo.mercado.dominio.Oferta;
import br.prospero.fo.mercado.dominio.RegraMercadoException;

import java.math.BigDecimal;
import java.util.List;

/** Valida quantidades, limites e coerência do livro antes de qualquer escrita. */
public final class ValidadorSnapshot {
    public static final int MAX_OFERTAS_POR_LADO = 100;
    public static final int MAX_NEGOCIOS = 200;
    public static final long MAX_QUANTIDADE = 1_000_000L;

    private ValidadorSnapshot() {
    }

    public static long quantidade(Long quantidade, Ativo ativo) {
        if (quantidade == null || quantidade <= 0 || quantidade > MAX_QUANTIDADE) {
            throw new RegraMercadoException("QUANTIDADE_INVALIDA", "Quantidade deve ser inteira, positiva e até 1000000.");
        }
        if (quantidade % ativo.lote() != 0) {
            throw new RegraMercadoException("QUANTIDADE_FORA_DO_LOTE", "Quantidade não é múltipla do lote do ativo.");
        }
        return quantidade;
    }

    public static void validar(List<Oferta> ofertas, List<Negocio> negocios) {
        long compras = ofertas.stream().filter(o -> o.lado() == Lado.COMPRA).count();
        if (compras > MAX_OFERTAS_POR_LADO || ofertas.size() - compras > MAX_OFERTAS_POR_LADO
                || negocios.size() > MAX_NEGOCIOS) {
            throw new RegraMercadoException("LIMITE_EXCEDIDO", "Snapshot excede o número máximo de ofertas ou negócios.");
        }
        BigDecimal melhorCompra = melhor(ofertas, Lado.COMPRA);
        BigDecimal melhorVenda = melhor(ofertas, Lado.VENDA);
        if (melhorCompra != null && melhorVenda != null && melhorCompra.compareTo(melhorVenda) >= 0) {
            throw new RegraMercadoException("LIVRO_CRUZADO", "Melhor compra deve ser menor que a melhor venda.");
        }
    }

    static BigDecimal melhor(List<Oferta> ofertas, Lado lado) {
        return ofertas.stream().filter(o -> o.lado() == lado).map(Oferta::preco)
                .reduce((a, b) -> (lado == Lado.COMPRA ? a.max(b) : a.min(b))).orElse(null);
    }
}
