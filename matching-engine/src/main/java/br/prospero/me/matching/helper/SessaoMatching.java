package br.prospero.me.matching.helper;

import br.prospero.me.livro.dominio.AdmissaoOco;
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
    private LocalDate sessao;

    public List<byte[]> processar(EnvelopeMatching.Comando comando) {
        var a = comando.admissao();
        if (sessao != null && !sessao.equals(a.sessao())) throw new IllegalArgumentException("Uma sessão por log.");
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

    public LocalDate sessao() { return sessao; }

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
