package br.prospero.mo.matching.helper;

import java.math.BigDecimal;
import static br.prospero.mo.matching.helper.EnvelopeMatching.exigir;

/** Valores exatos debitados da compra; o troco volta ao disponível, sem crédito ao vendedor. */
public final class CalculoConsumo {
    private CalculoConsumo() { }
    public record Compra(BigDecimal retido, BigDecimal custo, BigDecimal troco, long restante) { }

    public static Compra compra(long restante, long quantidade, BigDecimal teto, BigDecimal preco, BigDecimal valor) {
        exigir(quantidade>0 && quantidade<=restante,"restante");
        exigir(teto!=null && preco!=null && preco.signum()>0 && preco.compareTo(teto)<=0,"teto compra");
        BigDecimal q=BigDecimal.valueOf(quantidade);
        BigDecimal retido=teto.multiply(q).setScale(2,java.math.RoundingMode.UNNECESSARY);
        BigDecimal custo=preco.multiply(q).setScale(2,java.math.RoundingMode.UNNECESSARY);
        exigir(valor!=null && valor.compareTo(retido)>=0,"valor reserva");
        return new Compra(retido,custo,retido.subtract(custo),restante-quantidade);
    }
}
