package br.prospero.mo.mensageria.dominio;

/** Porta da outbox; gravar participa da transação corrente do chamador. */
public interface Outbox {
    void gravar(MensagemSaida mensagem);
}
