package br.prospero.mo.mensageria.dominio;

/** Reaplica uma mensagem em quarentena pelo mesmo caminho do consumidor do tópico, sem passar pelo Kafka. */
public interface Reaplicacao {
    boolean suporta(String topico);

    void reaplicar(MensagemQuarentena mensagem);
}
