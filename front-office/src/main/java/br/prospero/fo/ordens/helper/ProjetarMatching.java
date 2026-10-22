package br.prospero.fo.ordens.helper;

import br.prospero.fo.ordens.dominio.EstadoOrdem;
import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.dominio.LadoOrdem;
import br.prospero.fo.ordens.dominio.MotivoRejeicao;
import br.prospero.fo.ordens.dominio.Ordem;
import br.prospero.fo.ordens.dominio.PapelOco;
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
                || !ordem.dataSessao().equals(evento.sessao()) || (ordem.grupoOco() != null && evento.versao() < 3)
                || (evento.versao() == 4 && !EventoMatching.encerramento(evento.ticker(), evento.sessao()).equals(evento.causacao()))
                || (stop(ordem) && evento.causacao() == null)) {
            throw incoerente();
        }
        return switch (evento.detalhe()) {
            case EventoMatching.Aceita ignorado -> inicial(ordem)
                    ? atualizar(ordem, stop(ordem) ? EstadoOrdem.AGUARDANDO_GATILHO : EstadoOrdem.ABERTA, 0, 0, null, null) : ordem;
            case EventoMatching.StopPendente pendente -> pendente(ordem, pendente);
            case EventoMatching.StopAcionado acionado -> acionar(ordem, acionado);
            case EventoMatching.Oferta oferta -> oferta(ordem, oferta);
            case EventoMatching.Negocio negocio -> negocio(ordem, negocio, alvo, tick, lote);
            case EventoMatching.Encerrada encerrada -> encerrar(ordem, encerrada);
            case EventoMatching.Reduzida reduzida -> reduzir(ordem, reduzida);
            case EventoMatching.SessaoEncerrada marco -> throw incoerente();
            case EventoMatching.Rejeitada rejeitada -> {
                if ((stop(ordem) && acionada(ordem))
                        // O par rejeitado por falta de vaga pendente rejeita também o Take Profit (v3).
                        || (rejeitada.motivo() == MotivoRejeicao.LIMITE_STOPS_PENDENTES && ordem.grupoOco() == null
                            && (!stop(ordem) || acionada(ordem)))
                        || ordem.quantidadeExecutada() != 0 || ordem.quantidadeCancelada() != 0
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
        if (!o.tipo().comLimite() || (stop(o) && !acionada(o)) || o.validade() != Validade.DIA || o.lado() != e.lado()
                || o.precoLimite().compareTo(e.preco()) != 0) {
            throw incoerente();
        }
        // Oferta antiga nunca desfaz parcial/terminal, mesmo se o produtor trocar o ID indevidamente.
        if (o.quantidadeExecutada() != 0 || o.estado() == EstadoOrdem.CANCELADA || o.estado() == EstadoOrdem.EXECUTADA) {
            return o;
        }
        if (o.estado() == EstadoOrdem.REJEITADA || e.restante() != o.quantidadeVigente()) {
            throw incoerente();
        }
        return atualizar(o, EstadoOrdem.ABERTA, 0, 0, null, null);
    }

    private static Ordem negocio(Ordem o, EventoMatching.Negocio n, EventoMatching.Alvo alvo,
                                  BigDecimal tick, long lote) {
        boolean compra = alvo.equals(n.compra());
        if ((stop(o) && !acionada(o)) || (compra ? LadoOrdem.COMPRA : LadoOrdem.VENDA) != o.lado()
                || o.estado() == EstadoOrdem.REJEITADA || o.estado() == EstadoOrdem.CANCELADA
                || o.estado() == EstadoOrdem.EXECUTADA || n.quantidade() <= 0
                || n.quantidade() > o.quantidadeVigente() - o.quantidadeExecutada() || o.quantidadeCancelada() != 0
                || n.quantidade() % lote != 0 || n.preco().remainder(tick).signum() != 0) {
            throw incoerente();
        }
        if (o.tipo().comLimite()) {
            int comparacao = n.preco().compareTo(o.precoLimite());
            if (compra ? comparacao > 0 : comparacao < 0) {
                throw incoerente();
            }
        }
        long executada = o.quantidadeExecutada() + n.quantidade();
        return atualizar(o, executada == o.quantidadeVigente() ? EstadoOrdem.EXECUTADA : EstadoOrdem.PARCIALMENTE_EXECUTADA,
                executada, 0, null, null);
    }

    private static Ordem encerrar(Ordem o, EventoMatching.Encerrada e) {
        boolean excesso = "LIMITE_CASCATA".equals(e.motivo());
        // Irmã OCO cancelada: SL ainda sem acionamento e sem execução; TP com a execução já projetada.
        boolean irma = "CANCELADA_OCO".equals(e.motivo());
        // Fim da sessão: expira ofertas DIA e stops ainda pendentes (RF05c.4).
        boolean expirada = "EXPIRADA_DIA".equals(e.motivo());
        if ((irma && (o.grupoOco() == null || stop(o) && (acionada(o) || e.executada() != 0)))
                || (excesso && (!stop(o) || acionada(o) || e.executada() != 0))
                || (expirada && o.validade() != Validade.DIA)
                || (stop(o) && !excesso && !irma && !expirada && !acionada(o))
                || o.estado() == EstadoOrdem.REJEITADA || e.executada() != o.quantidadeExecutada()
                || e.cancelada() != o.quantidadeVigente() - o.quantidadeExecutada()) {
            throw incoerente();
        }
        if (("FOK_SEM_LIQUIDEZ".equals(e.motivo()) && (o.validade() != Validade.FOK || e.executada() != 0))
                || ("SALDO_CANCELADO_IOC".equals(e.motivo()) && o.validade() != Validade.IOC && o.tipo() != TipoOrdem.STOP)) {
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

    /** Só o Stop Loss pendente perde quantidade; a igualdade com o que o TP ainda deve é guarda cruzada do MO. */
    private static Ordem reduzir(Ordem o, EventoMatching.Reduzida e) {
        long vigente = o.quantidadeVigente() - e.reduzida();
        if (o.papelOco() != PapelOco.STOP_LOSS || acionada(o) || o.estado() == EstadoOrdem.REJEITADA
                || o.estado() == EstadoOrdem.CANCELADA || o.quantidadeExecutada() != 0 || vigente <= 0
                || e.restante() != vigente) {
            throw incoerente();
        }
        Ordem atualizada = atualizar(o, inicial(o) ? EstadoOrdem.AGUARDANDO_GATILHO : o.estado(), 0, 0, null, null);
        return comReducao(atualizada, o.quantidadeReduzida() + e.reduzida());
    }

    private static boolean stop(Ordem o) {
        return o.tipo().stop();
    }

    private static boolean acionada(Ordem o) {
        return o.estado() == EstadoOrdem.ABERTA || o.estado() == EstadoOrdem.PARCIALMENTE_EXECUTADA
                || o.estado() == EstadoOrdem.EXECUTADA
                || (o.estado() == EstadoOrdem.CANCELADA && !"LIMITE_CASCATA".equals(o.motivoEncerramento())
                    && !"CANCELADA_OCO".equals(o.motivoEncerramento()));
    }

    private static Ordem pendente(Ordem o, EventoMatching.StopPendente e) {
        if (!stop(o) || o.precoGatilho().compareTo(e.precoGatilho()) != 0 || o.estado() == EstadoOrdem.REJEITADA) {
            throw incoerente();
        }
        return inicial(o) ? atualizar(o, EstadoOrdem.AGUARDANDO_GATILHO, 0, 0, null, null) : o;
    }

    private static Ordem acionar(Ordem o, EventoMatching.StopAcionado e) {
        TipoOrdem esperado = o.tipo() == TipoOrdem.STOP ? TipoOrdem.MARKET : TipoOrdem.LIMIT;
        if (!stop(o) || e.tipoExecutavel() != esperado || o.estado() == EstadoOrdem.REJEITADA) throw incoerente();
        return inicial(o) || o.estado() == EstadoOrdem.AGUARDANDO_GATILHO
                ? atualizar(o, EstadoOrdem.ABERTA, 0, 0, null, null) : o;
    }

    private static boolean inicial(Ordem o) {
        return o.estado() == EstadoOrdem.RECEBIDA || o.estado() == EstadoOrdem.RESERVADA;
    }

    private static Ordem atualizar(Ordem o, EstadoOrdem estado, long executada, long cancelada,
                                   MotivoRejeicao rejeicao, String encerramento) {
        return comReducao(new Ordem(o.id(), o.conta(), o.ticker(), o.lado(), o.tipo(), o.validade(), o.quantidade(),
                o.precoLimite(), o.precoGatilho(), o.grupoOco(), o.papelOco(), o.grupoEnvio(), estado,
                o.dataSessao(), o.recebidaEm(), o.baseTunel(), rejeicao, executada, cancelada, encerramento), o.quantidadeReduzida());
    }

    private static Ordem comReducao(Ordem o, long reduzida) {
        return new Ordem(o.id(), o.conta(), o.ticker(), o.lado(), o.tipo(), o.validade(), o.quantidade(),
                o.precoLimite(), o.precoGatilho(), o.grupoOco(), o.papelOco(), o.grupoEnvio(), o.estado(),
                o.dataSessao(), o.recebidaEm(), o.baseTunel(), o.motivoRejeicao(), o.quantidadeExecutada(),
                o.quantidadeCancelada(), o.motivoEncerramento(), reduzida);
    }

    private static MatchingIncoerenteException incoerente() {
        return new MatchingIncoerenteException("MATCHING_INCOERENTE");
    }
}
