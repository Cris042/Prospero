package br.prospero.fo.ordens.helper;

import br.prospero.fo.ordens.dominio.EstadoOrdem;
import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.dominio.LadoOrdem;
import br.prospero.fo.ordens.dominio.MotivoRejeicao;
import br.prospero.fo.ordens.dominio.Ordem;
import br.prospero.fo.ordens.dominio.MatchingIncoerenteException;
import br.prospero.fo.ordens.dominio.TipoOrdem;
import br.prospero.fo.ordens.dominio.Validade;

import java.math.BigDecimal;

/** Projeção pura: não depende da chegada do resultado do risco em outro tópico. */
public final class ProjetarMatching {
    private ProjetarMatching() {
    }

    public static Ordem aplicar(Ordem ordem, EventoMatching evento, EventoMatching.Alvo alvo,
                                BigDecimal tick, long lote) {
        if (!ordem.id().equals(alvo.ordem()) || !ordem.grupoEnvio().equals(alvo.intencao())
                || !ordem.conta().equals(alvo.conta()) || !ordem.ticker().equals(evento.ticker())
                || !ordem.dataSessao().equals(evento.sessao()) || ordem.grupoOco() != null
                || (ordem.tipo() != TipoOrdem.LIMIT && ordem.tipo() != TipoOrdem.MARKET)) {
            throw incoerente();
        }
        return switch (evento.detalhe()) {
            case EventoMatching.Aceita ignorado -> inicial(ordem)
                    ? atualizar(ordem, EstadoOrdem.ABERTA, 0, 0, null, null) : ordem;
            case EventoMatching.Oferta oferta -> oferta(ordem, oferta);
            case EventoMatching.Negocio negocio -> negocio(ordem, negocio, alvo, tick, lote);
            case EventoMatching.Encerrada encerrada -> encerrar(ordem, encerrada);
            case EventoMatching.Rejeitada rejeitada -> {
                if (ordem.quantidadeExecutada() != 0 || ordem.quantidadeCancelada() != 0
                        || ordem.estado() == EstadoOrdem.EXECUTADA || ordem.estado() == EstadoOrdem.CANCELADA) {
                    throw incoerente();
                }
                if (ordem.estado() == EstadoOrdem.REJEITADA && ordem.motivoRejeicao() != rejeitada.motivo()) {
                    throw incoerente();
                }
                yield atualizar(ordem, EstadoOrdem.REJEITADA, 0, 0, rejeitada.motivo(), null);
            }
        };
    }

    private static Ordem oferta(Ordem o, EventoMatching.Oferta e) {
        if (o.tipo() != TipoOrdem.LIMIT || o.validade() != Validade.DIA || o.lado() != e.lado()
                || o.precoLimite().compareTo(e.preco()) != 0) {
            throw incoerente();
        }
        // Oferta antiga nunca desfaz parcial/terminal, mesmo se o produtor trocar o ID indevidamente.
        if (o.quantidadeExecutada() != 0 || o.estado() == EstadoOrdem.CANCELADA || o.estado() == EstadoOrdem.EXECUTADA) {
            return o;
        }
        if (o.estado() == EstadoOrdem.REJEITADA || e.restante() != o.quantidade()) {
            throw incoerente();
        }
        return atualizar(o, EstadoOrdem.ABERTA, 0, 0, null, null);
    }

    private static Ordem negocio(Ordem o, EventoMatching.Negocio n, EventoMatching.Alvo alvo,
                                  BigDecimal tick, long lote) {
        boolean compra = alvo.equals(n.compra());
        if ((compra ? LadoOrdem.COMPRA : LadoOrdem.VENDA) != o.lado()
                || o.estado() == EstadoOrdem.REJEITADA || o.estado() == EstadoOrdem.CANCELADA
                || o.estado() == EstadoOrdem.EXECUTADA || n.quantidade() <= 0
                || n.quantidade() > o.quantidade() - o.quantidadeExecutada() || o.quantidadeCancelada() != 0
                || n.quantidade() % lote != 0 || n.preco().remainder(tick).signum() != 0) {
            throw incoerente();
        }
        if (o.tipo() == TipoOrdem.LIMIT) {
            int comparacao = n.preco().compareTo(o.precoLimite());
            if (compra ? comparacao > 0 : comparacao < 0) {
                throw incoerente();
            }
        }
        long executada = o.quantidadeExecutada() + n.quantidade();
        return atualizar(o, executada == o.quantidade() ? EstadoOrdem.EXECUTADA : EstadoOrdem.PARCIALMENTE_EXECUTADA,
                executada, 0, null, null);
    }

    private static Ordem encerrar(Ordem o, EventoMatching.Encerrada e) {
        if (o.estado() == EstadoOrdem.REJEITADA || e.executada() != o.quantidadeExecutada()
                || e.cancelada() != o.quantidade() - o.quantidadeExecutada()) {
            throw incoerente();
        }
        if (("FOK_SEM_LIQUIDEZ".equals(e.motivo()) && (o.validade() != Validade.FOK || e.executada() != 0))
                || ("SALDO_CANCELADO_IOC".equals(e.motivo()) && o.validade() != Validade.IOC)) {
            throw incoerente();
        }
        boolean executada = "EXECUTADA".equals(e.motivo());
        if (executada != (e.cancelada() == 0) || (o.estado() == EstadoOrdem.CANCELADA
                && (!e.motivo().equals(o.motivoEncerramento()) || e.cancelada() != o.quantidadeCancelada()))) {
            throw incoerente();
        }
        return atualizar(o, executada ? EstadoOrdem.EXECUTADA : EstadoOrdem.CANCELADA,
                e.executada(), e.cancelada(), null, e.motivo());
    }

    private static boolean inicial(Ordem o) {
        return o.estado() == EstadoOrdem.RECEBIDA || o.estado() == EstadoOrdem.RESERVADA;
    }

    private static Ordem atualizar(Ordem o, EstadoOrdem estado, long executada, long cancelada,
                                   MotivoRejeicao rejeicao, String encerramento) {
        return new Ordem(o.id(), o.conta(), o.ticker(), o.lado(), o.tipo(), o.validade(), o.quantidade(),
                o.precoLimite(), o.precoGatilho(), o.grupoOco(), o.papelOco(), o.grupoEnvio(), estado,
                o.dataSessao(), o.recebidaEm(), o.baseTunel(), rejeicao, executada, cancelada, encerramento);
    }

    private static MatchingIncoerenteException incoerente() {
        return new MatchingIncoerenteException("MATCHING_INCOERENTE");
    }
}
