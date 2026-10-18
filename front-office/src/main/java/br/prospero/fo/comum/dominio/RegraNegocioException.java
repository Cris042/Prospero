package br.prospero.fo.comum.dominio;

/** Violação de regra com código estável e categoria, sem refletir a entrada recebida. */
public class RegraNegocioException extends RuntimeException {
    private final Categoria categoria;
    private final String codigo;

    public RegraNegocioException(Categoria categoria, String codigo, String mensagem) {
        super(mensagem);
        this.categoria = categoria;
        this.codigo = codigo;
    }

    public Categoria categoria() {
        return categoria;
    }

    public String codigo() {
        return codigo;
    }
}
