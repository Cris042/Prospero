package br.prospero.me.livro.dominio;

import br.prospero.me.livro.helper.FaixaPreco;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Núcleo puro do matching (ADR-013): um escritor por ativo, sem relógio, aleatoriedade nem I/O. A mesma sequência de
 * admissões produz sempre os mesmos eventos (RNF02). Prioridade preço-tempo, negócio ao preço da oferta passiva,
 * Market limitada à faixa do túnel sobre o último negócio do ME e ao preço máximo reservado; FOK verifica antes.
 */
public final class MotorMatching {
    public static final int MAX_NEGOCIOS_POR_COMANDO = 256;
    public static final int MAX_ORDENS_ATIVAS_POR_CONTA = 1000;
    private final Map<String, ParametrosAtivo> parametros;
    private final Map<String, EstadoAtivo> estados = new HashMap<>();

    public MotorMatching(Map<String, ParametrosAtivo> parametros) {
        this.parametros = Map.copyOf(parametros);
    }

    /** Estado do escritor único de um ativo: livro, último negócio, sequências e ordens já vistas. */
    private static final class EstadoAtivo {
        final Livro livro = new Livro();
        final Set<UUID> vistas = new HashSet<>();
        BigDecimal ultimo;
        LocalDate sessao;
        long negocios;
        long chegada;
    }

    public List<Evento> processar(Admissao a) {
        ParametrosAtivo p = parametros.get(a.ticker());
        if (p == null) {
            return List.of(new Evento.OrdemRejeitada(a.ordem(), a.ticker(), MotivoRejeicao.ATIVO_DESCONHECIDO));
        }
        EstadoAtivo e = estados.computeIfAbsent(a.ticker(), t -> new EstadoAtivo());
        MotivoRejeicao recusa = recusa(a, e);
        if (recusa != null) {
            return List.of(new Evento.OrdemRejeitada(a.ordem(), a.ticker(), recusa));
        }
        if (!a.sessao().equals(e.sessao)) {
            e.sessao = a.sessao();
            e.negocios = 0;
        }
        e.vistas.add(a.ordem());
        List<Evento> eventos = new ArrayList<>();
        eventos.add(new Evento.OrdemAceita(a.ordem(), a.ticker(), ++e.chegada));
        Limites limites = limites(a, e, p);
        if (a.validade() == Validade.FOK && disponivel(e.livro, a, limites) < a.quantidade()) {
            eventos.add(new Evento.OrdemEncerrada(a.ordem(), a.ticker(), MotivoEncerramento.FOK_SEM_LIQUIDEZ, 0,
                    a.quantidade()));
            return eventos;
        }
        long executada = executar(a, e, limites, eventos);
        encerrarOuRepousar(a, e, limites, executada, eventos);
        return eventos;
    }

    private MotivoRejeicao recusa(Admissao a, EstadoAtivo e) {
        boolean market = a.tipo() == TipoOrdem.MARKET;
        boolean compra = a.lado() == Lado.COMPRA;
        if ((market && (a.validade() == Validade.DIA || a.precoLimite() != null))
                || (!market && a.precoLimite() == null) || (compra != (a.precoMaximo() != null))) {
            return MotivoRejeicao.COMBINACAO_INVALIDA;
        }
        if (compra && !market && a.precoLimite().compareTo(a.precoMaximo()) > 0) {
            return MotivoRejeicao.PRECO_ACIMA_DA_RESERVA;
        }
        if (e.vistas.contains(a.ordem())) {
            return MotivoRejeicao.ORDEM_DUPLICADA;
        }
        if (e.livro.ativas(a.conta()) >= MAX_ORDENS_ATIVAS_POR_CONTA) {
            return MotivoRejeicao.LIMITE_ORDENS_ATIVAS;
        }
        return null;
    }

    /** Pior preço aceitável pelo agressor: limite (Limit) ou faixa do túnel e reserva (Market). */
    private record Limites(BigDecimal preco, boolean faixa) {
    }

    private static Limites limites(Admissao a, EstadoAtivo e, ParametrosAtivo p) {
        if (a.tipo() == TipoOrdem.LIMIT) {
            return new Limites(a.precoLimite(), false);
        }
        BigDecimal base = e.ultimo != null ? e.ultimo : p.referencia();
        if (a.lado() == Lado.VENDA) {
            return new Limites(FaixaPreco.piso(base, p.tick(), p.tunelPercentual()), true);
        }
        BigDecimal teto = FaixaPreco.teto(base, p.tick(), p.tunelPercentual());
        return new Limites(teto.min(a.precoMaximo()), true);
    }

    private static boolean aceitavel(Lado agressor, BigDecimal preco, Limites limites) {
        int comparacao = preco.compareTo(limites.preco());
        return agressor == Lado.COMPRA ? comparacao <= 0 : comparacao >= 0;
    }

    /** Quantidade executável a preços aceitáveis, sem tocar o livro (FOK). */
    private static long disponivel(Livro livro, Admissao a, Limites limites) {
        long total = 0;
        for (var nivel : livro.lado(a.lado().oposto()).entrySet()) {
            if (!aceitavel(a.lado(), nivel.getKey(), limites) || total >= a.quantidade()) {
                break;
            }
            for (OfertaViva oferta : nivel.getValue()) {
                total += oferta.restante();
            }
        }
        return total;
    }

    private long executar(Admissao a, EstadoAtivo e, Limites limites, List<Evento> eventos) {
        long executada = 0;
        int negocios = 0;
        Lado oposto = a.lado().oposto();
        while (executada < a.quantidade() && negocios < MAX_NEGOCIOS_POR_COMANDO) {
            OfertaViva passiva = e.livro.melhor(oposto);
            if (passiva == null || !aceitavel(a.lado(), passiva.preco, limites)) {
                break;
            }
            long quantidade = Math.min(a.quantidade() - executada, passiva.restante());
            executada += quantidade;
            passiva.executada += quantidade;
            negocios++;
            e.ultimo = passiva.preco;
            boolean compra = a.lado() == Lado.COMPRA;
            eventos.add(new Evento.Negocio(a.ticker() + "-" + e.sessao + "-" + (++e.negocios), a.ticker(), passiva.preco,
                    quantidade, compra ? a.ordem() : passiva.ordem, compra ? a.conta() : passiva.conta,
                    compra ? passiva.ordem : a.ordem(), compra ? passiva.conta : a.conta(), a.lado()));
            if (passiva.restante() == 0) {
                e.livro.removerCabeca(passiva);
                eventos.add(new Evento.OrdemEncerrada(passiva.ordem, a.ticker(), MotivoEncerramento.EXECUTADA,
                        passiva.quantidade, 0));
            }
        }
        return executada;
    }

    private static void encerrarOuRepousar(Admissao a, EstadoAtivo e, Limites limites, long executada,
                                           List<Evento> eventos) {
        long restante = a.quantidade() - executada;
        if (restante == 0) {
            eventos.add(new Evento.OrdemEncerrada(a.ordem(), a.ticker(), MotivoEncerramento.EXECUTADA, executada, 0));
            return;
        }
        OfertaViva contraparte = e.livro.melhor(a.lado().oposto());
        boolean interrompidaPorLimite = contraparte != null && aceitavel(a.lado(), contraparte.preco, limites);
        if (interrompidaPorLimite) {
            eventos.add(new Evento.OrdemEncerrada(a.ordem(), a.ticker(), MotivoEncerramento.LIMITE_NEGOCIOS, executada,
                    restante));
            return;
        }
        if (a.tipo() == TipoOrdem.LIMIT && a.validade() == Validade.DIA) {
            e.livro.repousar(new OfertaViva(a.ordem(), a.conta(), a.lado(), a.precoLimite(), e.chegada, a.quantidade(),
                    executada));
            eventos.add(new Evento.OfertaNoLivro(a.ordem(), a.ticker(), a.lado(), a.precoLimite(), restante));
            return;
        }
        MotivoEncerramento motivo = a.tipo() == TipoOrdem.LIMIT ? MotivoEncerramento.SALDO_CANCELADO_IOC
                : contraparte != null && limites.faixa() ? MotivoEncerramento.FORA_DA_FAIXA
                : MotivoEncerramento.SEM_LIQUIDEZ;
        eventos.add(new Evento.OrdemEncerrada(a.ordem(), a.ticker(), motivo, executada, restante));
    }
}
