package br.prospero.fo.ordens.helper;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.dominio.RegraNegocioException;
import br.prospero.fo.ordens.dominio.BaseTunel;
import br.prospero.fo.ordens.dominio.LadoOrdem;
import br.prospero.fo.ordens.dominio.OrigemBase;
import br.prospero.fo.ordens.dominio.ParametrosAtivo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Regras da RF03 (ADR-011): lote, tick, coerência do Stop Limit e túnel inclusivo com limites arredondados
 * ao tick para dentro. Exatas em BigDecimal; nenhuma conversão para ponto flutuante.
 */
public final class ValidacaoPreco {
    private static final BigDecimal CEM = new BigDecimal("100");

    private ValidacaoPreco() {
    }

    public static void lote(long quantidade, ParametrosAtivo ativo) {
        if (quantidade % ativo.lote() != 0) {
            throw regra("QUANTIDADE_FORA_DO_LOTE", "Quantidade deve ser múltipla do lote do ativo.",
                    Map.of("lote", String.valueOf(ativo.lote())));
        }
    }

    /** Preço ausente (null) não se aplica ao tipo e é ignorado. */
    public static void tick(BigDecimal preco, ParametrosAtivo ativo) {
        if (preco != null && preco.remainder(ativo.tick()).signum() != 0) {
            throw regra("PRECO_FORA_DO_TICK", "Preço deve ser múltiplo do tick do ativo.",
                    Map.of("tick", ativo.tick().toPlainString()));
        }
    }

    /** Stop Limit: venda com limite ≤ disparo; compra com limite ≥ disparo (igualdade aceita). */
    public static void stopLimit(LadoOrdem lado, BigDecimal limite, BigDecimal gatilho) {
        int comparacao = limite.compareTo(gatilho);
        if (lado == LadoOrdem.VENDA ? comparacao > 0 : comparacao < 0) {
            throw regra("STOP_LIMIT_INCOERENTE", "No Stop Limit de venda o limite não pode passar do disparo; na compra, não pode ficar abaixo.",
                    Map.of());
        }
    }

    /** Último negócio da sessão; sem ele (antes do primeiro negócio ou cotação defasada), a referência do ativo. */
    public static BaseTunel base(Optional<BigDecimal> ultimoDaSessao, ParametrosAtivo ativo) {
        return ultimoDaSessao.map(preco -> new BaseTunel(preco, OrigemBase.ULTIMO))
                .orElseGet(() -> new BaseTunel(ativo.precoReferencia(), OrigemBase.REFERENCIA));
    }

    /** Faixa inclusiva do túnel. */
    public record Faixa(BigDecimal minimo, BigDecimal maximo) {
    }

    /** Limites inclusivos arredondados ao tick para dentro do intervalo. */
    public static Faixa limites(BaseTunel base, BigDecimal tick, BigDecimal percentual) {
        BigDecimal desvio = base.preco().multiply(percentual).divide(CEM);
        BigDecimal minimo = base.preco().subtract(desvio).divide(tick, 0, RoundingMode.CEILING).multiply(tick);
        BigDecimal maximo = base.preco().add(desvio).divide(tick, 0, RoundingMode.FLOOR).multiply(tick);
        return new Faixa(minimo.setScale(tick.scale()), maximo.setScale(tick.scale()));
    }

    public static void tunel(BigDecimal preco, BaseTunel base, BigDecimal tick, BigDecimal percentual) {
        if (preco == null) {
            return;
        }
        Faixa faixa = limites(base, tick, percentual);
        if (preco.compareTo(faixa.minimo()) < 0 || preco.compareTo(faixa.maximo()) > 0) {
            throw regra("PRECO_FORA_DO_TUNEL", "Preço fora do túnel permitido para o ativo.", Map.of(
                    "minimo", faixa.minimo().toPlainString(), "maximo", faixa.maximo().toPlainString(),
                    "base", base.preco().toPlainString(), "origemBase", base.origem().name()));
        }
    }

    private static RegraNegocioException regra(String codigo, String mensagem, Map<String, String> detalhes) {
        return new RegraNegocioException(Categoria.REGRA_VIOLADA, codigo, mensagem, detalhes);
    }
}
