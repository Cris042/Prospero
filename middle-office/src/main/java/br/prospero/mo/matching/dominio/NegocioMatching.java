package br.prospero.mo.matching.dominio;

import java.math.BigDecimal;

import static br.prospero.mo.matching.dominio.Guarda.exigir;

/**
 * Negócio entre duas reservas travadas, com as pernas OCO de cada lado quando houver (nulas fora de OCO). A execução
 * consome só o recurso reservado (ADR-013): restante, teto da compra e limites das ordens.
 */
public record NegocioMatching(ReservaMatching compra, ReservaMatching venda, PernaOco pernaCompra,
                              PernaOco pernaVenda, long quantidade, BigDecimal preco) {

    /** Guardas antes de qualquer consumo, na ordem do contrato. */
    public void exigirExecutavel(boolean v2) {
        exigir(executavel(compra, pernaCompra, v2) && executavel(venda, pernaVenda, v2), "stop nao acionado");
        exigir(quantidade <= compra.restante() && quantidade <= venda.restante(), "restante");
        exigir((pernaCompra == null || quantidade <= pernaCompra.restante())
                && (pernaVenda == null || quantidade <= pernaVenda.restante()), "restante perna");
        exigir(preco.compareTo(compra.teto()) <= 0, "teto compra");
        BigDecimal limiteCompra = pernaCompra == null ? compra.limite() : pernaCompra.limite();
        BigDecimal limiteVenda = pernaVenda == null ? venda.limite() : pernaVenda.limite();
        exigir(limiteCompra == null || preco.compareTo(limiteCompra) <= 0, "limite compra");
        exigir(limiteVenda == null || preco.compareTo(limiteVenda) >= 0, "limite venda");
    }

    private static boolean executavel(ReservaMatching reserva, PernaOco perna, boolean v2) {
        if (perna == null) return !reserva.stop() || v2 && reserva.acionada();
        // TP negocia até a captura (que o encerra); SL só depois de acionado.
        return perna.viva() && (perna.takeProfit() || perna.acionada());
    }
}
