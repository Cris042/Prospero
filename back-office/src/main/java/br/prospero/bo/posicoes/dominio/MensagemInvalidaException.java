package br.prospero.bo.posicoes.dominio;

/** Mensagem fora do contrato: não produz efeito e o consumo avança; a mensagem nunca contém o conteúdo do evento. */
public class MensagemInvalidaException extends RuntimeException {
    public MensagemInvalidaException(String motivo) {
        super(motivo);
    }
}
