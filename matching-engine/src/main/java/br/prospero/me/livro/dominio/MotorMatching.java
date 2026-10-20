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
import java.util.TreeMap;
import java.util.UUID;

/**
 * Núcleo puro do matching (ADR-013): um escritor por instância, sem relógio, aleatoriedade nem I/O. A mesma sequência de
 * admissões produz sempre os mesmos eventos (RNF02). Prioridade preço-tempo, negócio ao preço da oferta passiva,
 * Market limitada à faixa do túnel sobre o último negócio do ME na sessão e ao preço máximo reservado; FOK verifica
 * antes, inclusive o teto de negócios por comando. Reentrega de uma ordem já vista não produz eventos (idempotente):
 * nenhuma rejeição terminal é emitida para uma ordem que pode estar viva no livro.
 */
public final class MotorMatching {
    public static final int MAX_NEGOCIOS_POR_COMANDO = 256;
    public static final int MAX_ORDENS_ATIVAS_POR_CONTA = 1000;
    public static final int MAX_STOPS_PENDENTES = 500;
    public static final int MAX_ATIVACOES_POR_COMANDO = 32;
    public static final long MAX_QUANTIDADE = 1_000_000;
    private static final BigDecimal PRECO_MAXIMO = new BigDecimal("1000000.00");
    private final Map<String, ParametrosAtivo> parametros;
    private final Map<String, EstadoAtivo> estados = new HashMap<>();
    /** Ordens já decididas, em todos os ativos/sessões; fechamento não autoriza ressuscitar uma identidade. */
    private final Set<UUID> vistas = new HashSet<>();

    public MotorMatching(Map<String, ParametrosAtivo> parametros) {
        this.parametros = Map.copyOf(parametros);
    }

    /** Estado do escritor único de um ativo: livro, último negócio da sessão e sequências. */
    private static final class EstadoAtivo {
        final Livro livro = new Livro();
        final StopsPendentes stops = new StopsPendentes();
        // Uma contagem cobre livro, pendentes, agressor e selecionadas aguardando na fila.
        final Map<String, Integer> ativasPorConta = new HashMap<>();
        BigDecimal ultimo;
        LocalDate sessao;
        LocalDate ultimaEncerrada;
        long negocios;
        long chegada;

        int ativas(String conta) {
            return ativasPorConta.getOrDefault(conta, 0);
        }

        void ativar(String conta) {
            ativasPorConta.merge(conta, 1, Integer::sum);
        }

        void desativar(String conta) {
            ativasPorConta.compute(conta, (chave, total) -> {
                if (total == null || total <= 0) {
                    throw new IllegalStateException("Ordem terminal fora da contagem ativa.");
                }
                return total == 1 ? null : total - 1;
            });
        }
    }

    /** Orçamento do comando raiz; a fila drenada não renova ativações nem negócios. */
    private static final class ContextoComando {
        final List<Evento> eventos = new ArrayList<>();
        final ArrayDeque<Admissao> acionadas = new ArrayDeque<>();
        int selecionadas;
        int negocios;
    }

    public List<Evento> processar(Admissao a) {
        if (!vistas.add(a.ordem())) {
            return List.of();
        }
        ParametrosAtivo p = parametros.get(a.ticker());
        if (p == null) {
            return List.of(new Evento.OrdemRejeitada(a.ordem(), a.ticker(), MotivoRejeicao.ATIVO_DESCONHECIDO));
        }
        EstadoAtivo e = estados.computeIfAbsent(a.ticker(), t -> new EstadoAtivo());
        MotivoRejeicao recusa = recusa(a, e, p);
        if (recusa != null) {
            return List.of(new Evento.OrdemRejeitada(a.ordem(), a.ticker(), recusa));
        }
        if (!a.sessao().equals(e.sessao)) {
            // Nova sessão: IDs de negócio recomeçam e a base da faixa volta à referência (ADR-013).
            e.sessao = a.sessao();
            e.negocios = 0;
            e.ultimo = null;
            e.chegada = 0;
        }
        ContextoComando contexto = new ContextoComando();
        long sequencia = ++e.chegada;
        e.ativar(a.conta());
        contexto.eventos.add(new Evento.OrdemAceita(a.ordem(), a.ticker(), sequencia));
        if (stop(a)) {
            receberStop(a, sequencia, e, contexto);
        } else {
            negociar(a, sequencia, e, p, contexto);
        }
        drenarAcionadas(e, p, contexto);
        return List.copyOf(contexto.eventos);
    }

    private static boolean stop(Admissao a) {
        return a.tipo() == TipoOrdem.STOP || a.tipo() == TipoOrdem.STOP_LIMIT;
    }

    private static void receberStop(Admissao a, long sequencia, EstadoAtivo e, ContextoComando contexto) {
        if (StopsPendentes.acionavel(a, e.ultimo)) {
            selecionar(a, e, contexto);
            return;
        }
        e.stops.guardar(a, sequencia);
        contexto.eventos.add(new Evento.StopPendente(a.ordem(), a.ticker(), a.precoGatilho()));
    }

    private void negociar(Admissao a, long sequencia, EstadoAtivo e, ParametrosAtivo p, ContextoComando contexto) {
        Limites limites = limites(a, e, p);
        if (a.validade() == Validade.FOK && disponivel(e.livro, a, limites) < a.quantidade()) {
            encerrar(a, e, MotivoEncerramento.FOK_SEM_LIQUIDEZ, 0, contexto.eventos);
            return;
        }
        long executada = executar(a, e, limites, contexto);
        encerrarOuRepousar(a, sequencia, e, limites, executada, contexto);
    }

    private static void selecionar(Admissao stop, EstadoAtivo e, ContextoComando contexto) {
        if (contexto.selecionadas == MAX_ATIVACOES_POR_COMANDO) {
            encerrar(stop, e, MotivoEncerramento.LIMITE_CASCATA, 0, contexto.eventos);
            return;
        }
        contexto.selecionadas++;
        contexto.acionadas.addLast(stop);
    }

    private void drenarAcionadas(EstadoAtivo e, ParametrosAtivo p, ContextoComando contexto) {
        while (!contexto.acionadas.isEmpty()) {
            Admissao stop = contexto.acionadas.removeFirst();
            TipoOrdem tipo = stop.tipo() == TipoOrdem.STOP ? TipoOrdem.MARKET : TipoOrdem.LIMIT;
            Admissao convertida = new Admissao(stop.ordem(), stop.intencao(), stop.conta(), stop.ticker(), stop.lado(),
                    tipo, tipo == TipoOrdem.MARKET ? Validade.IOC : Validade.DIA, stop.quantidade(),
                    stop.precoLimite(), stop.precoMaximo(), stop.sessao());
            long sequencia = ++e.chegada;
            contexto.eventos.add(new Evento.StopAcionado(stop.ordem(), stop.ticker(), tipo, sequencia));
            if (contexto.negocios == MAX_NEGOCIOS_POR_COMANDO) {
                encerrar(convertida, e, MotivoEncerramento.LIMITE_NEGOCIOS, 0, contexto.eventos);
                continue;
            }
            negociar(convertida, sequencia, e, p, contexto);
        }
    }

    /**
     * Comando interno da RF05c.1a, por ativo/data: expira somente saldos vivos, em sequência de chegada.
     * Repetição de data já encerrada é inócua. O adaptador Kafka ainda não recebe este comando: continua
     * restrito a uma sessão por log, sem virada operacional nem liberação financeira comprovada por este método.
     */
    public List<Evento> encerrarSessao(String ticker, LocalDate sessao) {
        if (ticker == null || !parametros.containsKey(ticker) || sessao == null) {
            throw new IllegalArgumentException("Ativo e sessão de encerramento inválidos.");
        }
        EstadoAtivo e = estados.get(ticker);
        if (e != null && e.ultimaEncerrada != null && !sessao.isAfter(e.ultimaEncerrada)) {
            return List.of();
        }
        if (e != null && e.sessao != null && !sessao.equals(e.sessao)) {
            throw new IllegalArgumentException("Encerramento diverge da sessão do ativo.");
        }
        if (e == null) {
            e = new EstadoAtivo();
            estados.put(ticker, e);
        }
        e.sessao = sessao;
        Map<Long, Evento> eventos = new TreeMap<>();
        for (OfertaViva oferta : e.livro.retirarTodas()) {
            eventos.put(oferta.sequencia, new Evento.OrdemEncerrada(oferta.ordem, ticker, MotivoEncerramento.EXPIRADA_DIA,
                    oferta.executada, oferta.restante()));
        }
        for (StopsPendentes.Pendente pendente : e.stops.retirarTodas()) {
            Admissao ordem = pendente.admissao();
            eventos.put(pendente.sequencia(), new Evento.OrdemEncerrada(ordem.ordem(), ticker,
                    MotivoEncerramento.EXPIRADA_DIA, 0, ordem.quantidade()));
        }
        e.ativasPorConta.clear();
        e.ultimaEncerrada = sessao;
        return List.copyOf(eventos.values());
    }

    private static MotivoRejeicao recusa(Admissao a, EstadoAtivo e, ParametrosAtivo p) {
        if (e.sessao != null && a.sessao().isBefore(e.sessao)) {
            return MotivoRejeicao.SESSAO_ANTERIOR;
        }
        if (a.sessao().equals(e.ultimaEncerrada)) {
            return MotivoRejeicao.SESSAO_ENCERRADA;
        }
        if (e.sessao != null && a.sessao().isAfter(e.sessao) && !e.sessao.equals(e.ultimaEncerrada)) {
            return MotivoRejeicao.SESSAO_NAO_ENCERRADA;
        }
        if (a.quantidade() > MAX_QUANTIDADE) {
            return MotivoRejeicao.QUANTIDADE_INVALIDA;
        }
        if (!precoValido(a.precoLimite(), p) || !precoValido(a.precoMaximo(), p)
                || !precoValido(a.precoGatilho(), p)) {
            return MotivoRejeicao.PRECO_INVALIDO;
        }
        boolean market = a.tipo() == TipoOrdem.MARKET;
        boolean stop = stop(a);
        boolean limit = a.tipo() == TipoOrdem.LIMIT || a.tipo() == TipoOrdem.STOP_LIMIT;
        boolean compra = a.lado() == Lado.COMPRA;
        if ((market && a.validade() == Validade.DIA) || (stop && a.validade() != Validade.DIA)
                || limit != (a.precoLimite() != null) || stop != (a.precoGatilho() != null)
                || compra != (a.precoMaximo() != null)) {
            return MotivoRejeicao.COMBINACAO_INVALIDA;
        }
        if (a.tipo() == TipoOrdem.STOP_LIMIT && (compra
                ? a.precoLimite().compareTo(a.precoGatilho()) < 0
                : a.precoLimite().compareTo(a.precoGatilho()) > 0)) {
            return MotivoRejeicao.COMBINACAO_INVALIDA;
        }
        if (compra && limit && a.precoLimite().compareTo(a.precoMaximo()) > 0) {
            return MotivoRejeicao.PRECO_ACIMA_DA_RESERVA;
        }
        if (e.ativas(a.conta()) >= MAX_ORDENS_ATIVAS_POR_CONTA) {
            return MotivoRejeicao.LIMITE_ORDENS_ATIVAS;
        }
        if (stop && !StopsPendentes.acionavel(a, e.ultimo) && e.stops.quantidade() >= MAX_STOPS_PENDENTES) {
            return MotivoRejeicao.LIMITE_STOPS_PENDENTES;
        }
        return null;
    }

    /** Ausente, ou positivo, até 1.000.000,00, com no máximo duas casas e múltiplo do tick. */
    private static boolean precoValido(BigDecimal preco, ParametrosAtivo p) {
        return preco == null || (ParametrosAtivo.representacaoLimitada(preco)
                && preco.signum() > 0 && preco.compareTo(PRECO_MAXIMO) <= 0
                && preco.stripTrailingZeros().scale() <= 2 && preco.remainder(p.tick()).signum() == 0);
    }

    /** Pior preço aceitável pelo agressor: limite (Limit) ou faixa do túnel e reserva (Market). */
    private record Limites(BigDecimal preco, MotivoEncerramento motivoCorte) {
    }

    private static Limites limites(Admissao a, EstadoAtivo e, ParametrosAtivo p) {
        if (a.tipo() == TipoOrdem.LIMIT) {
            return new Limites(a.precoLimite(), MotivoEncerramento.SALDO_CANCELADO_IOC);
        }
        BigDecimal base = e.ultimo != null ? e.ultimo : p.referencia();
        if (a.lado() == Lado.VENDA) {
            return new Limites(FaixaPreco.piso(base, p.tick(), p.tunelPercentual()), MotivoEncerramento.FORA_DA_FAIXA);
        }
        BigDecimal teto = FaixaPreco.teto(base, p.tick(), p.tunelPercentual());
        return teto.compareTo(a.precoMaximo()) <= 0 ? new Limites(teto, MotivoEncerramento.FORA_DA_FAIXA)
                : new Limites(a.precoMaximo(), MotivoEncerramento.LIMITADA_PELA_RESERVA);
    }

    private static boolean aceitavel(Lado agressor, BigDecimal preco, Limites limites) {
        int comparacao = preco.compareTo(limites.preco());
        return agressor == Lado.COMPRA ? comparacao <= 0 : comparacao >= 0;
    }

    /**
     * Quantidade executável a preços aceitáveis dentro do teto de negócios por comando, sem tocar o livro (FOK).
     * Para assim que cobre a quantidade pedida: custo limitado a MAX_NEGOCIOS_POR_COMANDO ofertas.
     */
    private static long disponivel(Livro livro, Admissao a, Limites limites) {
        long total = 0;
        int ofertas = 0;
        for (var nivel : livro.lado(a.lado().oposto()).entrySet()) {
            if (!aceitavel(a.lado(), nivel.getKey(), limites)) {
                break;
            }
            for (OfertaViva oferta : nivel.getValue()) {
                if (total >= a.quantidade() || ofertas == MAX_NEGOCIOS_POR_COMANDO) {
                    return total;
                }
                total += oferta.restante();
                ofertas++;
            }
        }
        return total;
    }

    private long executar(Admissao a, EstadoAtivo e, Limites limites, ContextoComando contexto) {
        long executada = 0;
        List<Evento> eventos = contexto.eventos;
        Lado oposto = a.lado().oposto();
        while (executada < a.quantidade() && contexto.negocios < MAX_NEGOCIOS_POR_COMANDO) {
            OfertaViva passiva = e.livro.melhor(oposto);
            if (passiva == null || !aceitavel(a.lado(), passiva.preco, limites)) {
                break;
            }
            long quantidade = Math.min(a.quantidade() - executada, passiva.restante());
            executada += quantidade;
            passiva.executada += quantidade;
            contexto.negocios++;
            e.ultimo = passiva.preco;
            boolean compra = a.lado() == Lado.COMPRA;
            eventos.add(new Evento.Negocio(a.ticker() + "-" + e.sessao + "-" + (++e.negocios), a.ticker(), passiva.preco,
                    quantidade, compra ? a.ordem() : passiva.ordem, compra ? a.conta() : passiva.conta,
                    compra ? passiva.ordem : a.ordem(), compra ? passiva.conta : a.conta(), a.lado()));
            if (passiva.restante() == 0) {
                e.livro.removerCabeca(passiva);
                e.desativar(passiva.conta);
                eventos.add(new Evento.OrdemEncerrada(passiva.ordem, a.ticker(), MotivoEncerramento.EXECUTADA,
                        passiva.quantidade, 0));
            }
            capturarStops(e, contexto);
        }
        return executada;
    }

    private static void capturarStops(EstadoAtivo e, ContextoComando contexto) {
        for (Admissao acionada : e.stops.retirarAcionados(e.ultimo)) {
            selecionar(acionada, e, contexto);
        }
    }

    private static void encerrar(Admissao a, EstadoAtivo e, MotivoEncerramento motivo, long executada,
                                 List<Evento> eventos) {
        e.desativar(a.conta());
        eventos.add(new Evento.OrdemEncerrada(a.ordem(), a.ticker(), motivo, executada, a.quantidade() - executada));
    }

    private static void encerrarOuRepousar(Admissao a, long sequencia, EstadoAtivo e, Limites limites, long executada,
                                           ContextoComando contexto) {
        List<Evento> eventos = contexto.eventos;
        long restante = a.quantidade() - executada;
        if (restante == 0) {
            encerrar(a, e, MotivoEncerramento.EXECUTADA, executada, eventos);
            return;
        }
        if (contexto.negocios == MAX_NEGOCIOS_POR_COMANDO) {
            encerrar(a, e, MotivoEncerramento.LIMITE_NEGOCIOS, executada, eventos);
            return;
        }
        if (a.tipo() == TipoOrdem.LIMIT && a.validade() == Validade.DIA) {
            e.livro.repousar(new OfertaViva(a.ordem(), a.conta(), a.lado(), a.precoLimite(), sequencia, a.quantidade(),
                    executada));
            eventos.add(new Evento.OfertaNoLivro(a.ordem(), a.ticker(), a.lado(), a.precoLimite(), restante));
            return;
        }
        OfertaViva contraparte = e.livro.melhor(a.lado().oposto());
        MotivoEncerramento motivo = a.tipo() == TipoOrdem.LIMIT || contraparte != null ? limites.motivoCorte()
                : MotivoEncerramento.SEM_LIQUIDEZ;
        encerrar(a, e, motivo, executada, eventos);
    }
}
