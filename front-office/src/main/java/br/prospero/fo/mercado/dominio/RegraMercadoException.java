package br.prospero.fo.mercado.dominio;

/** Violação de regra de entrada ou de coerência do snapshot, com código estável. */
public class RegraMercadoException extends RuntimeException {
    private final String codigo;

    public RegraMercadoException(String codigo, String mensagem) {
        super(mensagem);
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }
}
