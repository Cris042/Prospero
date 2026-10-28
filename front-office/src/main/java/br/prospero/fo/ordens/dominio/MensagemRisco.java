package br.prospero.fo.ordens.dominio;

/** O que o tópico risco.resultados.v1 transporta para o FO. */
public sealed interface MensagemRisco permits ResultadoRisco, ResultadoCancelamento {
}
