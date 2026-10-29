package br.prospero.me.matching.helper;

import java.time.LocalDate;

/**
 * Comando de outra data num log que já tem sessão (uma sessão por log, ADR-013). Não é falha transitória: o log
 * é imutável e o replay sempre para no mesmo offset. O procedimento de recuperação está no HLD-004.
 */
public final class SessaoDivergenteException extends IllegalArgumentException {
    public static final String PROCEDIMENTO = "docs/hlds/HLD-004-matching-engine.md#replay-bloqueado-por-sessão-divergente";
    private final LocalDate sessaoLog;
    private final LocalDate sessaoComando;

    public SessaoDivergenteException(LocalDate sessaoLog, LocalDate sessaoComando) {
        super("Uma sessão por log: o log está na sessão " + sessaoLog + " e o comando traz " + sessaoComando + ".");
        this.sessaoLog = sessaoLog;
        this.sessaoComando = sessaoComando;
    }

    public LocalDate sessaoLog() { return sessaoLog; }

    public LocalDate sessaoComando() { return sessaoComando; }

    /** Diagnóstico sem payload, conta ou ordem: só datas, partição e offset, seguro para log e health. */
    public String diagnostico(int particao, long offset) {
        return "Log de matching com mais de uma sessão: partição " + particao + ", offset " + offset
                + " traz a sessão " + sessaoComando + ", mas o log está na sessão " + sessaoLog
                + ". O replay sempre para neste ponto. Procedimento: " + PROCEDIMENTO;
    }
}
