package br.prospero.fo.cotacao;

import br.prospero.fo.cotacao.dominio.MutacaoCotacao;
import br.prospero.fo.cotacao.helper.ProjetarCotacao;
import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.dominio.LadoOrdem;
import br.prospero.fo.ordens.dominio.MotivoRejeicao;
import br.prospero.fo.ordens.dominio.TipoOrdem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjetarCotacaoTest {
    private static final EventoMatching.Alvo COMPRA = alvo();
    private static final EventoMatching.Alvo VENDA = alvo();

    private static EventoMatching.Alvo alvo() {
        return new EventoMatching.Alvo(UUID.randomUUID(), UUID.randomUUID(), "CONTA-002");
    }

    @Test
    void ofertaNoLivroRepousaComORestante() {
        assertEquals(List.of(new MutacaoCotacao.Repousar(VENDA.ordem(), "VENDA", new BigDecimal("10.05"), 7)),
                ProjetarCotacao.mutacoes(new EventoMatching.Oferta(VENDA, LadoOrdem.VENDA, new BigDecimal("10.05"), 7)));
    }

    @Test
    void negocioConsomeSoAPassivaEAtualizaUltimoEVolumes() {
        var negocio = new EventoMatching.Negocio("ALFA3-2026-10-22-1", new BigDecimal("10.05"), 3, COMPRA, VENDA, LadoOrdem.COMPRA);
        assertEquals(List.of(new MutacaoCotacao.Consumir(VENDA.ordem(), 3), new MutacaoCotacao.Negociar(new BigDecimal("10.05"), 3)),
                ProjetarCotacao.mutacoes(negocio));
        var vendaAgressora = new EventoMatching.Negocio("ALFA3-2026-10-22-2", new BigDecimal("9.95"), 2, COMPRA, VENDA, LadoOrdem.VENDA);
        assertEquals(new MutacaoCotacao.Consumir(COMPRA.ordem(), 2), ProjetarCotacao.mutacoes(vendaAgressora).getFirst());
    }

    @Test
    void encerramentoRetiraReducaoAjustaEMarcoEsvazia() {
        assertEquals(List.of(new MutacaoCotacao.Retirar(VENDA.ordem())),
                ProjetarCotacao.mutacoes(new EventoMatching.Encerrada(VENDA, "EXPIRADA_DIA", 0, 7)));
        assertEquals(List.of(new MutacaoCotacao.Ajustar(VENDA.ordem(), 4)),
                ProjetarCotacao.mutacoes(new EventoMatching.Reduzida(VENDA, 3, 4)));
        assertEquals(List.of(new MutacaoCotacao.Encerrar()), ProjetarCotacao.mutacoes(new EventoMatching.SessaoEncerrada(2)));
    }

    @Test
    void fatosSemEfeitoNoLivroNaoMudamNada() {
        for (EventoMatching.Detalhe d : List.<EventoMatching.Detalhe>of(new EventoMatching.Aceita(COMPRA, 1),
                new EventoMatching.Rejeitada(COMPRA, MotivoRejeicao.values()[0]),
                new EventoMatching.StopPendente(COMPRA, new BigDecimal("9.00")),
                new EventoMatching.StopAcionado(COMPRA, TipoOrdem.LIMIT, 2))) {
            assertEquals(List.of(), ProjetarCotacao.mutacoes(d), d.getClass().getSimpleName());
        }
    }
}
