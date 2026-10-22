package br.prospero.mo.matching.dominio;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static br.prospero.mo.matching.dominio.Guarda.executavel;
import static br.prospero.mo.matching.dominio.Guarda.exigir;

/**
 * Progresso de uma perna OCO; caixa/custódia continuam na reserva do grupo (RF05c.3b). Registro carregado do banco;
 * isento das regras 8 e 9 do Object Calisthenics como representação.
 */
public record PernaOco(UUID ordem, UUID intencao, String papel, String tipo, BigDecimal limite, BigDecimal gatilho,
                       long quantidade, long executada, long reduzida, long cancelada, boolean acionada,
                       String estado) {

    public long restante() {
        return quantidade - executada - reduzida - cancelada;
    }

    public boolean viva() {
        return !estado.equals("ENCERRADA");
    }

    public boolean takeProfit() {
        return papel.equals("TAKE_PROFIT");
    }

    /**
     * Guardas de um fato desta perna e o efeito local resultante; só o último terminal do grupo libera o restante
     * reservado (decidido pelo repositório após encerrar a perna).
     *
     * @param admissao id determinístico da admissão do grupo, esperado na correlação
     * @param irma     carrega (e trava) a outra perna somente nos pontos em que a guarda a consulta, preservando a
     *                 ordem das verificações e dos locks
     */
    public Transicao transicao(FatoMatching fato, ReservaMatching grupo, UUID admissao, Supplier<PernaOco> irma) {
        exigir(fato.correlacao().equals(admissao), "alvo correlacao");
        return switch (fato.tipo()) {
            case "ORDEM_ACEITA" -> {
                exigir(viva(), "perna encerrada");
                yield Transicao.NENHUMA;
            }
            case "STOP_PENDENTE" -> pendente(fato);
            case "ORDEM_REDUZIDA" -> reducao(fato, irma);
            case "STOP_ACIONADO" -> acionamento(fato, irma);
            case "OFERTA_NO_LIVRO" -> oferta(fato, grupo);
            case "ORDEM_REJEITADA" -> rejeicao(fato, irma);
            case "ORDEM_ENCERRADA" -> terminal(fato, irma);
            default -> throw new IllegalStateException("tipo validado na fronteira");
        };
    }

    private boolean stopLossPendente() {
        return !takeProfit() && !acionada && estado.equals("ATIVA");
    }

    private Transicao pendente(FatoMatching fato) {
        exigir(stopLossPendente(), "stop pendente");
        exigir(gatilho.compareTo(fato.precoGatilho()) == 0, "gatilho");
        return Transicao.NENHUMA;
    }

    private Transicao reducao(FatoMatching fato, Supplier<PernaOco> irma) {
        exigir(stopLossPendente(), "reducao perna");
        PernaOco takeProfit = irma.get();
        exigir(fato.reduzida() < restante() && fato.restante() == restante() - fato.reduzida()
                && fato.restante() == takeProfit.quantidade() - takeProfit.executada(), "reducao restante");
        return Transicao.REDUZIR;
    }

    private Transicao acionamento(FatoMatching fato, Supplier<PernaOco> irma) {
        exigir(stopLossPendente() && !irma.get().viva(), "stop acionamento");
        exigir(fato.tipoExecutavel().equals(executavel(tipo)), "tipo executavel");
        return Transicao.ACIONAR_STOP;
    }

    private Transicao oferta(FatoMatching fato, ReservaMatching grupo) {
        exigir(viva() && (takeProfit() || tipo.equals("STOP_LIMIT") && acionada), "oferta tipo");
        exigir(fato.restante() == restante(), "oferta restante");
        exigir(limite != null && limite.compareTo(fato.preco()) == 0, "oferta preco");
        exigir(fato.lado().equals(grupo.ladoDoRecurso()), "oferta lado");
        return Transicao.NENHUMA;
    }

    private Transicao rejeicao(FatoMatching fato, Supplier<PernaOco> irma) {
        exigir(viva() && executada == 0 && reduzida == 0 && !acionada, "rejeicao executada");
        exigir(!fato.motivo().equals("LIMITE_STOPS_PENDENTES") || !takeProfit() || irma.get().viva(), "rejeicao stop");
        return Transicao.ENCERRAR;
    }

    private Transicao terminal(FatoMatching fato, Supplier<PernaOco> irma) {
        exigir(viva(), "terminal divergente");
        exigir(fato.executada() == executada, "executada");
        exigir(fato.cancelada() == restante(), "cancelada");
        String motivo = fato.motivo();
        PernaOco outra = irma.get();
        switch (motivo) {
            case "EXPIRADA_DIA" -> { }
            case "CANCELADA_OCO" -> exigir(takeProfit() ? outra.viva() : !outra.viva() && !acionada && executada == 0,
                    "cancelamento oco");
            case "LIMITE_CASCATA" -> exigir(!takeProfit() && !acionada && executada == 0 && !outra.viva(), "cascata");
            default -> {
                exigir(takeProfit() ? Set.of("EXECUTADA", "LIMITE_NEGOCIOS").contains(motivo) : acionada,
                        "terminal perna");
                exigir(!motivo.equals("FOK_SEM_LIQUIDEZ"), "terminal FOK");
                exigir(!motivo.equals("SALDO_CANCELADO_IOC") || tipo.equals("STOP"), "terminal IOC");
                exigir(motivo.equals("EXECUTADA") == (restante() == 0), "execucao terminal");
            }
        }
        return Transicao.ENCERRAR;
    }
}
