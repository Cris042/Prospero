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
}
