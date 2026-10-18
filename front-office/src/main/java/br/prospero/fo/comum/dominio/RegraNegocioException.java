package br.prospero.fo.comum.dominio;

import java.util.Map;

/** Violação de regra com código estável, categoria e detalhes públicos opcionais, sem refletir a entrada recebida. */
public class RegraNegocioException extends RuntimeException {
    private final Categoria categoria;
    private final String codigo;
    private final Map<String, String> detalhes;

    public RegraNegocioException(Categoria categoria, String codigo, String mensagem) {
        this(categoria, codigo, mensagem, Map.of());
    }

    public RegraNegocioException(Categoria categoria, String codigo, String mensagem, Map<String, String> detalhes) {
        super(mensagem);
        this.categoria = categoria;
        this.codigo = codigo;
        this.detalhes = Map.copyOf(detalhes);
    }

    public Categoria categoria() {
        return categoria;
    }

    public String codigo() {
        return codigo;
    }

    public Map<String, String> detalhes() {
        return detalhes;
    }
}
