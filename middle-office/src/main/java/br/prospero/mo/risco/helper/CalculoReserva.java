package br.prospero.mo.risco.helper;

import br.prospero.mo.risco.dominio.ComandoRisco;
import br.prospero.mo.risco.dominio.Exigencia;
import br.prospero.mo.risco.dominio.Lado;
import br.prospero.mo.risco.dominio.MotivoRejeicao;
import br.prospero.mo.risco.dominio.ParametrosRisco;
import br.prospero.mo.risco.dominio.Perna;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Recurso exigido pela intenção (ADR-012), em BigDecimal exato. Venda reserva custódia, e o que passar dela vira
 * venda a descoberto no repositório (ADR-021); compra reserva
 * caixa: Limit/Stop Limit pelo limite, Market pelo teto do túnel sobre a base e Stop pelo teto sobre o gatilho.
 * O par OCO é mutuamente exclusivo: reserva o maior custo entre as pernas, ou a quantidade uma vez.
 */
public final class CalculoReserva {
    private static final BigDecimal CEM = new BigDecimal("100");

    private CalculoReserva() {
    }

    public static Exigencia exigencia(ComandoRisco comando, ParametrosRisco parametros) {
        for (Perna perna : comando.pernas()) {
            if (perna.quantidade() > parametros.quantidadeMaxima()) {
                return new Exigencia.Recusa(MotivoRejeicao.QUANTIDADE_MAXIMA_EXCEDIDA);
            }
            if (perna.quantidade() % parametros.lote() != 0) {
                return new Exigencia.Recusa(MotivoRejeicao.QUANTIDADE_FORA_DO_LOTE);
            }
        }
        if (comando.lado() == Lado.VENDA) {
            return new Exigencia.Custodia(comando.pernas().stream().mapToLong(Perna::quantidade).max().orElseThrow());
        }
        BigDecimal maior = null;
        for (Perna perna : comando.pernas()) {
            BigDecimal unitario = precoUnitario(perna, comando.precoBase(), parametros);
            if (unitario == null) {
                return new Exigencia.Recusa(MotivoRejeicao.PRECO_BASE_AUSENTE);
            }
            BigDecimal custo = unitario.multiply(BigDecimal.valueOf(perna.quantidade())).setScale(2, RoundingMode.UNNECESSARY);
            maior = maior == null || custo.compareTo(maior) > 0 ? custo : maior;
        }
        return new Exigencia.Caixa(maior);
    }

    /** Quantidade que a intenção pode executar: o par OCO é exclusivo, então conta a maior perna uma vez. */
    public static long quantidade(ComandoRisco comando) {
        return comando.pernas().stream().mapToLong(Perna::quantidade).max().orElseThrow();
    }

    /** Preço unitário máximo que a compra pode pagar; a RF05 não executa acima dele. */
    static BigDecimal precoUnitario(Perna perna, BigDecimal base, ParametrosRisco parametros) {
        return switch (perna.tipo()) {
            case LIMIT, STOP_LIMIT -> perna.precoLimite();
            case MARKET -> base == null ? null : teto(base, parametros);
            case STOP -> perna.precoGatilho() == null ? null : teto(perna.precoGatilho(), parametros);
        };
    }

    /** ⌊p × (1 + túnel) / tick⌋ × tick, o mesmo máximo inclusivo do túnel do FO (ADR-011). */
    public static BigDecimal teto(BigDecimal preco, ParametrosRisco parametros) {
        BigDecimal bruto = preco.multiply(CEM.add(parametros.tunelPercentual())).divide(CEM);
        return bruto.divide(parametros.tick(), 0, RoundingMode.FLOOR).multiply(parametros.tick()).setScale(2,
                RoundingMode.UNNECESSARY);
    }
}
