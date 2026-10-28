package br.prospero.fo.comum.dominio;

/**
 * Mensagem de tipo reconhecido que esta versão ainda não sabe aplicar (ex.: cancelamento sem coordenação). Diferente
 * de {@link MensagemInvalidaException}, nunca é descartada: o offset não avança, a partição repete com espera crescente
 * e a métrica registra o motivo. O motivo é uma constante estática, nunca derivada do conteúdo da mensagem.
 */
public class ParticaoBloqueadaException extends RuntimeException {
    public ParticaoBloqueadaException(String motivo) {
        super(motivo);
    }
}
