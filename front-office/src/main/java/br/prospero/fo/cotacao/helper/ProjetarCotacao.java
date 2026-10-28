package br.prospero.fo.cotacao.helper;

import br.prospero.fo.cotacao.dominio.MutacaoCotacao;
import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.dominio.LadoOrdem;

import java.util.List;

/**
 * Traduz um fato do matching em mutações do livro e da sessão (ADR-014). O lado passivo do negócio é o oposto do
 * agressor; o agressor só repousa depois dos negócios (OFERTA_NO_LIVRO), então nunca é consumido aqui.
 */
public final class ProjetarCotacao {
    private ProjetarCotacao() {
    }

    public static List<MutacaoCotacao> mutacoes(EventoMatching.Detalhe detalhe) {
        return switch (detalhe) {
            case EventoMatching.Oferta o -> List.of(new MutacaoCotacao.Repousar(o.alvo().ordem(), o.lado().name(), o.preco(), o.restante()));
            case EventoMatching.Negocio n -> List.of(
                    new MutacaoCotacao.Consumir((n.agressor() == LadoOrdem.COMPRA ? n.venda() : n.compra()).ordem(), n.quantidade()),
                    new MutacaoCotacao.Negociar(n.preco(), n.quantidade()));
            case EventoMatching.Encerrada e -> List.of(new MutacaoCotacao.Retirar(e.alvo().ordem()));
            case EventoMatching.Reduzida r -> List.of(new MutacaoCotacao.Ajustar(r.alvo().ordem(), r.restante()));
            case EventoMatching.SessaoEncerrada ignorado -> List.of(new MutacaoCotacao.Encerrar());
            case EventoMatching.Aceita ignorado -> List.of();
            case EventoMatching.Rejeitada ignorado -> List.of();
            case EventoMatching.StopPendente ignorado -> List.of();
            case EventoMatching.StopAcionado ignorado -> List.of();
            // A decisão do cancelamento não move o livro nem o último preço; o terminal (Encerrada) é que retira.
            case EventoMatching.CancelamentoDecidido ignorado -> List.of();
        };
    }
}
