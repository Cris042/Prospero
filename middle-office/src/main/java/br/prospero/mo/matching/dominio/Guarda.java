package br.prospero.mo.matching.dominio;

import br.prospero.mo.mensageria.dominio.MensagemInvalidaException;

/** Mesma exceção e mensagem da fronteira ("matching: campo"), sem conteúdo do evento. */
final class Guarda {
    private Guarda() {
    }

    static void exigir(boolean condicao, String campo) {
        if (!condicao) {
            throw new MensagemInvalidaException("matching: " + campo);
        }
    }

    /** Tipo em que o stop acionado executa: Stop vira Market; Stop Limit vira Limit (ADR-013). */
    static String tipoExecutavel(String tipoStop) {
        return tipoStop.equals("STOP") ? "MARKET" : "LIMIT";
    }
}
