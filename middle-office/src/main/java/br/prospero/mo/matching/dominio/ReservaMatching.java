package br.prospero.mo.matching.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static br.prospero.mo.matching.dominio.Guarda.executavel;
import static br.prospero.mo.matching.dominio.Guarda.exigir;

/**
 * Reserva por intenção travada para aplicar um fato do ME (ADR-013). Grupo OCO não tem tipo próprio: os tipos ficam
 * nas pernas. Registro carregado do banco; isento das regras 8 e 9 do Object Calisthenics como representação.
 */
public record ReservaMatching(UUID intencao, UUID ordem, String conta, String ticker, String recurso, String tipo,
                              String validade, LocalDate sessao, long inicial, long restante,
                              BigDecimal limite, BigDecimal teto, BigDecimal valor, String estado, BigDecimal gatilho,
                              boolean acionada, boolean oco) {

    public boolean stop() {
        return "STOP".equals(tipo) || "STOP_LIMIT".equals(tipo);
    }

    /**
     * Guardas de um fato de ordem simples (fora de OCO), na ordem do contrato, e o efeito local resultante.
     *
     * @param v2       evento v2 ou superior (com causação)
     * @param admissao id determinístico da admissão desta reserva, esperado na correlação
     */
    public Transicao transicao(FatoMatching fato, boolean v2, UUID admissao) {
        exigir(!stop() || v2, "versao stop");
        if (v2) exigir(fato.correlacao().equals(admissao), "alvo correlacao");
        return switch (fato.tipo()) {
            case "STOP_PENDENTE" -> pendente(fato);
            case "STOP_ACIONADO" -> acionamento(fato);
            case "ORDEM_REJEITADA" -> rejeicao(fato, admissao);
            case "ORDEM_ENCERRADA" -> terminal(fato);
            case "OFERTA_NO_LIVRO" -> oferta(fato);
            default -> {
                exigir(fato.correlacao().equals(admissao), "aceita correlacao");
                exigir(!estado.equals("ENCERRADA"), "reserva encerrada");
                yield Transicao.NENHUMA;
            }
        };
    }

    private Transicao pendente(FatoMatching fato) {
        exigir(stop() && !acionada && estado.equals("ATIVA"), "stop pendente");
        exigir(gatilho != null && gatilho.compareTo(fato.precoGatilho()) == 0, "gatilho");
        return Transicao.NENHUMA;
    }

    private Transicao acionamento(FatoMatching fato) {
        exigir(stop() && !acionada && estado.equals("ATIVA") && gatilho != null, "stop acionamento");
        exigir(fato.tipoExecutavel().equals(executavel(tipo)), "tipo executavel");
        return Transicao.ACIONAR_STOP;
    }

    private Transicao rejeicao(FatoMatching fato, UUID admissao) {
        exigir(fato.correlacao().equals(admissao), "rejeicao correlacao");
        exigir(inicial == restante, "rejeicao executada");
        exigir(!stop() || !acionada, "rejeicao apos acionamento");
        exigir(!fato.motivo().equals("LIMITE_STOPS_PENDENTES") || stop() && !acionada, "rejeicao stop");
        return Transicao.ENCERRAR;
    }

    private Transicao terminal(FatoMatching fato) {
        exigir(fato.executada() == inicial - restante, "executada");
        exigir(fato.cancelada() == restante, "cancelada");
        String motivo = fato.motivo();
        if (motivo.equals("EXPIRADA_DIA")) {
            // Expira ofertas DIA e stops pendentes; libera exatamente o saldo restante.
            exigir(validade.equals("DIA"), "expiracao validade");
            return Transicao.ENCERRAR;
        }
        boolean excesso = motivo.equals("LIMITE_CASCATA");
        exigir(!excesso || stop() && !acionada && inicial == restante, "cascata");
        exigir(!stop() || excesso || acionada, "terminal stop nao acionado");
        exigir(!motivo.equals("FOK_SEM_LIQUIDEZ") || validade.equals("FOK") && inicial == restante, "terminal FOK");
        exigir(!motivo.equals("SALDO_CANCELADO_IOC") || (validade.equals("IOC") || tipo.equals("STOP") && acionada),
                "terminal IOC");
        exigir(motivo.equals("EXECUTADA") == (restante == 0), "execucao terminal");
        return Transicao.ENCERRAR;
    }

    private Transicao oferta(FatoMatching fato) {
        exigir(estado.equals("ATIVA") || estado.equals("PARCIAL"), "reserva encerrada");
        exigir(fato.restante() == restante, "oferta restante");
        exigir((tipo.equals("LIMIT") || tipo.equals("STOP_LIMIT") && acionada) && validade.equals("DIA"), "oferta tipo");
        exigir(limite != null && limite.compareTo(fato.preco()) == 0, "oferta preco");
        exigir(fato.lado().equals(ladoDoRecurso()), "oferta lado");
        return Transicao.NENHUMA;
    }

    /** Caixa reserva compra; custódia reserva venda. */
    String ladoDoRecurso() {
        return recurso.equals("CAIXA") ? "COMPRA" : "VENDA";
    }
}
