package br.prospero.mo.risco.helper;

import br.prospero.mo.mensageria.dominio.MensagemInvalidaException;

/** Envelope de risco fora do contrato (ADR-012). */
public class EnvelopeInvalidoException extends MensagemInvalidaException {
    public EnvelopeInvalidoException(String motivo) {
        super(motivo);
    }
}
