package br.prospero.mo.liquidacao.dominio;

/** Registro recebido de liquidacao.eventos.v1: chave, bytes e posição no tópico, sem depender do cliente Kafka. */
public record RegistroLiquidacao(String chave, byte[] valor, int particao, long deslocamento) {
}
