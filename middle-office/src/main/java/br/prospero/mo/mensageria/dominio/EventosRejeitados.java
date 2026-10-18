package br.prospero.mo.mensageria.dominio;

/** Porta do diagnóstico limitado de mensagens fora do contrato: posição no tópico e motivo, nunca o conteúdo. */
public interface EventosRejeitados {
    void registrar(String topico, int particao, long deslocamento, String motivo);
}
