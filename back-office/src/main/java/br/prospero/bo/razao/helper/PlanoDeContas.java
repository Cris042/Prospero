package br.prospero.bo.razao.helper;

import br.prospero.bo.razao.dominio.*;

import java.util.List;

import static br.prospero.bo.razao.dominio.ContaContabil.*;
import static br.prospero.bo.razao.dominio.Natureza.CREDITO;
import static br.prospero.bo.razao.dominio.Natureza.DEBITO;

/**
 * Lançamentos D0 do ADR-016 (exemplo confirmado: 100 ALFA3 × 20,00), uma transação por lado e contraparte
 * "Câmara simulada". Autonegociação gera os dois lados para a mesma conta.
 */
public final class PlanoDeContas {
    private PlanoDeContas() { }

    public static List<TransacaoContabil> d0(FatoNegocio fato) {
        Montante dinheiro = fato.financeiro();
        Montante ativo = fato.ativo();
        var comprador = new TransacaoContabil(fato.negocio(), LadoNegocio.COMPRADOR, fato.compra().conta(), Momento.D0,
                List.of(new Partida(CAMARA, DEBITO, dinheiro), new Partida(CAIXA, CREDITO, dinheiro),
                        new Partida(CUSTODIA_A_RECEBER, DEBITO, ativo), new Partida(CAMARA, CREDITO, ativo)));
        var vendedor = new TransacaoContabil(fato.negocio(), LadoNegocio.VENDEDOR, fato.venda().conta(), Momento.D0,
                List.of(new Partida(CAMARA, DEBITO, ativo), new Partida(CUSTODIA, CREDITO, ativo),
                        new Partida(CAIXA_A_RECEBER, DEBITO, dinheiro), new Partida(CAMARA, CREDITO, dinheiro)));
        return List.of(comprador, vendedor);
    }

    /**
     * Lançamento D+2 de um lado: o comprador recebe a custódia, o vendedor recebe o caixa. Venda a descoberto
     * (ADR-021) usa as mesmas partidas: a custódia já saiu no D0, e o saldo negativo da conta CUSTODIA é a custódia
     * a entregar até a recompra.
     */
    public static TransacaoContabil d2(String negocio, LadoNegocio lado, String conta, Montante montante) {
        ContaContabil destino = lado == LadoNegocio.COMPRADOR ? CUSTODIA : CAIXA;
        ContaContabil origem = lado == LadoNegocio.COMPRADOR ? CUSTODIA_A_RECEBER : CAIXA_A_RECEBER;
        return new TransacaoContabil(negocio, lado, conta, Momento.D2,
                List.of(new Partida(destino, DEBITO, montante), new Partida(origem, CREDITO, montante)));
    }
}
