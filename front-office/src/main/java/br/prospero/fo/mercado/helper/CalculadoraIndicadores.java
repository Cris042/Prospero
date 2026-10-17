package br.prospero.fo.mercado.helper;

import br.prospero.fo.mercado.dominio.Ativo;
import br.prospero.fo.mercado.dominio.EstadoLivro;
import br.prospero.fo.mercado.dominio.Indicadores;
import br.prospero.fo.mercado.dominio.Lado;
import br.prospero.fo.mercado.dominio.Negocio;
import br.prospero.fo.mercado.dominio.SnapshotMercado;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Último = último negócio da sessão; variação absoluta e percentual sobre a referência
 * (duas casas, HALF_EVEN); volume em quantidade e financeiro da sessão. Sem negócio, ausentes.
 */
public final class CalculadoraIndicadores {
    private static final BigDecimal CEM = new BigDecimal("100");

    private CalculadoraIndicadores() {
    }

    public static Indicadores calcular(Ativo ativo, SnapshotMercado snapshot) {
        if (snapshot == null) {
            return new Indicadores(null, null, null, null, null, null, null, null, EstadoLivro.SEM_DADOS);
        }
        BigDecimal compra = ValidadorSnapshot.melhor(snapshot.ofertas(), Lado.COMPRA);
        BigDecimal venda = ValidadorSnapshot.melhor(snapshot.ofertas(), Lado.VENDA);
        BigDecimal spread = compra != null && venda != null ? venda.subtract(compra) : null;
        if (snapshot.negocios().isEmpty()) {
            return new Indicadores(null, null, null, compra, venda, spread, null, null, estado(compra, venda));
        }
        BigDecimal ultimo = snapshot.negocios().getLast().preco();
        BigDecimal variacao = ultimo.subtract(ativo.precoReferencia());
        BigDecimal percentual = variacao.multiply(CEM).divide(ativo.precoReferencia(), 2, RoundingMode.HALF_EVEN);
        long quantidade = 0;
        BigDecimal financeiro = BigDecimal.ZERO.setScale(2);
        for (Negocio negocio : snapshot.negocios()) {
            quantidade = Math.addExact(quantidade, negocio.quantidade());
            financeiro = financeiro.add(negocio.preco().multiply(BigDecimal.valueOf(negocio.quantidade())));
        }
        return new Indicadores(ultimo, variacao, percentual, compra, venda, spread, quantidade, financeiro,
                estado(compra, venda));
    }

    static EstadoLivro estado(BigDecimal compra, BigDecimal venda) {
        if (compra == null && venda == null) {
            return EstadoLivro.VAZIO;
        }
        if (venda == null) {
            return EstadoLivro.SOMENTE_COMPRA;
        }
        return compra == null ? EstadoLivro.SOMENTE_VENDA : EstadoLivro.COMPLETO;
    }
}
