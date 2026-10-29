package br.prospero.me.matching.helper;

import br.prospero.me.livro.dominio.AdmissaoOco;
import br.prospero.me.livro.dominio.AlvoCancelamento;
import br.prospero.me.livro.dominio.MotorMatching;
import br.prospero.me.livro.dominio.ParametrosAtivo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Estado descartável: após qualquer falha de publicação, somente um novo replay pode reutilizar o log. */
public final class SessaoMatching {
    private final MotorMatching motor = new MotorMatching(parametros());
    private final Map<UUID, EnvelopeMatching.Comando> ordens = new HashMap<>();
    private final Map<UUID, UUID> intencoes = new HashMap<>();
    private final java.util.Set<String> encerrados = new java.util.HashSet<>();
    /** Comandos de cancelamento já decididos, por id: limitado ao orçamento do log e reconstruído pelo replay. */
    private final Map<UUID, AlvoCancelamento> cancelamentos = new HashMap<>();
    private LocalDate sessao;

    public List<byte[]> processar(EnvelopeMatching.Comando comando) {
        if (comando.encerramento() != null) return encerrar(comando);
        if (comando.cancelamento() != null) return cancelar(comando);
        var a = comando.admissao();
        if (sessao != null && !sessao.equals(a.sessao())) throw new SessaoDivergenteException(sessao, a.sessao());
        var anterior = ordens.get(a.ordem());
        if (anterior != null && !anterior.equals(comando)) throw new IllegalArgumentException("Admissão divergente.");
        // O par registra a intenção pelo Take Profit; o Stop Loss compartilha a mesma admissão.
        if (comando.oco()) {
            var irma = ordens.get(comando.stopLoss().ordem());
            if (irma != null && !irma.equals(comando) || (anterior == null) != (irma == null)) {
                throw new IllegalArgumentException("Admissão divergente.");
            }
        }
        UUID ordemDaIntencao = intencoes.get(a.intencao());
        if (ordemDaIntencao != null && !ordemDaIntencao.equals(a.ordem())) throw new IllegalArgumentException("Intenção divergente.");
        int novas = comando.oco() ? 2 : 1;
        if (anterior == null && ordens.size() + novas > 30_000) throw new IllegalStateException("Orçamento de ordens esgotado.");
        sessao = a.sessao();
        ordens.putIfAbsent(a.ordem(), comando);
        if (comando.oco()) ordens.putIfAbsent(comando.stopLoss().ordem(), comando);
        intencoes.putIfAbsent(a.intencao(), a.ordem());
        var eventos = comando.oco() ? motor.processarOco(new AdmissaoOco(a, comando.stopLoss())) : motor.processar(a);
        List<byte[]> envelopes = new ArrayList<>(eventos.size());
        for (int i = 0; i < eventos.size(); i++) envelopes.add(EnvelopeMatching.evento(comando, eventos.get(i), i, ordens));
        return List.copyOf(envelopes);
    }

    /**
     * Encerramento (RF05c.4): expira o ativo e publica o marco uma vez. Repetição e data diferente da sessão do log
     * não têm efeito, sem parar o escritor; abrir nova data continua fora (uma sessão por log).
     */
    private List<byte[]> encerrar(EnvelopeMatching.Comando comando) {
        var e = comando.encerramento();
        if (sessao != null && !sessao.equals(e.sessao()) || !encerrados.add(e.ticker())) return List.of();
        sessao = e.sessao();
        var eventos = motor.encerrarSessao(e.ticker(), e.sessao());
        List<byte[]> envelopes = new ArrayList<>(eventos.size() + 1);
        for (int i = 0; i < eventos.size(); i++) envelopes.add(EnvelopeMatching.evento(comando, eventos.get(i), i, ordens));
        envelopes.add(EnvelopeMatching.marco(comando, eventos.size(), eventos.size()));
        return List.copyOf(envelopes);
    }

    /**
     * Retirada manual (RF10c2): repetição do mesmo comando não tem efeito nem evento; o mesmo id com alvo diferente é
     * inconsistência. Ordem desconhecida é recusada pelo núcleo sem abrir sessão nem criar estado.
     */
    private List<byte[]> cancelar(EnvelopeMatching.Comando comando) {
        var alvo = comando.cancelamento();
        var anterior = cancelamentos.get(comando.id());
        if (anterior != null) {
            if (!anterior.equals(alvo)) throw new IllegalArgumentException("Cancelamento divergente.");
            return List.of();
        }
        if (cancelamentos.size() >= 30_000) throw new IllegalStateException("Orçamento de cancelamentos esgotado.");
        cancelamentos.put(comando.id(), alvo);
        return EnvelopeMatching.cancelamento(comando, motor.cancelarManual(alvo));
    }

    public LocalDate sessao() { return sessao; }

    /**
     * Sessão a gravar no checkpoint: admissão de outra data para o escritor (uma sessão por log); encerramento nunca
     * para nem troca a sessão já persistida, e só a fixa quando o log ainda não tem sessão.
     */
    public static LocalDate sessaoDoCheckpoint(EnvelopeMatching.Comando comando, LocalDate persistida) {
        if (comando.encerramento() != null || comando.cancelamento() != null) {
            return persistida != null ? persistida : comando.sessao();
        }
        if (persistida != null && !persistida.equals(comando.sessao())) {
            throw new SessaoDivergenteException(persistida, comando.sessao());
        }
        return comando.sessao();
    }

    private static Map<String, ParametrosAtivo> parametros() {
        Map<String, ParametrosAtivo> ativos = new HashMap<>();
        String[] tickers = {"ALFA3", "BETA3", "GAMA3", "DELTA3", "OMEGA3"};
        for (int i = 0; i < tickers.length; i++) {
            String ticker = tickers[i];
            ativos.put(ticker, new ParametrosAtivo(ticker, new BigDecimal(i == 4 ? "0.05" : "0.01"),
                    BigDecimal.valueOf((i + 1) * 10L).setScale(2), new BigDecimal("10")));
        }
        return Map.copyOf(ativos);
    }
}
