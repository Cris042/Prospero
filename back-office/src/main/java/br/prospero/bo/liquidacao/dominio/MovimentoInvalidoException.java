package br.prospero.bo.liquidacao.dominio;

public class MovimentoInvalidoException extends RuntimeException {
    private final int status;
    private final String codigo;
    public MovimentoInvalidoException(int status, String codigo) {
        super(codigo);
        this.status = status;
        this.codigo = codigo;
    }
    public int status() { return status; }
    public String codigo() { return codigo; }
}
