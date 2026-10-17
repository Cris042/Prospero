package br.prospero.fo.mercado.helper;

import br.prospero.fo.mercado.dominio.LadoAgregado;
import br.prospero.fo.mercado.dominio.Lado;
import br.prospero.fo.mercado.dominio.NivelLivro;
import br.prospero.fo.mercado.dominio.Oferta;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** Agrega ofertas por preço: compras decrescentes, vendas crescentes, cinco níveis exibidos. */
public final class AgregadorLivro {
    public static final int NIVEIS_EXIBIDOS = 5;

    private AgregadorLivro() {
    }

    public static LadoAgregado agregar(List<Oferta> ofertas, Lado lado) {
        Comparator<BigDecimal> ordem = lado == Lado.COMPRA ? Comparator.reverseOrder() : Comparator.naturalOrder();
        TreeMap<BigDecimal, long[]> porPreco = new TreeMap<>(ordem);
        long total = 0;
        for (Oferta oferta : ofertas) {
            if (oferta.lado() != lado) {
                continue;
            }
            long[] nivel = porPreco.computeIfAbsent(oferta.preco(), p -> new long[2]);
            nivel[0] = Math.addExact(nivel[0], oferta.quantidade());
            nivel[1]++;
            total = Math.addExact(total, oferta.quantidade());
        }
        List<NivelLivro> niveis = new ArrayList<>();
        long acumulado = 0;
        for (var entrada : porPreco.entrySet()) {
            if (niveis.size() == NIVEIS_EXIBIDOS) {
                break;
            }
            acumulado = Math.addExact(acumulado, entrada.getValue()[0]);
            niveis.add(new NivelLivro(entrada.getKey(), entrada.getValue()[0], (int) entrada.getValue()[1], acumulado));
        }
        return new LadoAgregado(niveis, porPreco.size(), total);
    }
}
