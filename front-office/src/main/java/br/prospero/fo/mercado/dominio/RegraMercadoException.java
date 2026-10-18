package br.prospero.fo.mercado.dominio;

import br.prospero.fo.comum.dominio.Categoria;
import br.prospero.fo.comum.dominio.RegraNegocioException;

/** Violação de regra de entrada ou de coerência do snapshot, com código estável. */
public class RegraMercadoException extends RegraNegocioException {
    public RegraMercadoException(String codigo, String mensagem) {
        super(categoria(codigo), codigo, mensagem);
    }

    private static Categoria categoria(String codigo) {
        return switch (codigo) {
            case "ATIVO_DESCONHECIDO", "PREPARACAO_DESABILITADA" -> Categoria.NAO_ENCONTRADO;
            case "NAMESPACE_INVALIDO" -> Categoria.ENTRADA_INVALIDA;
            case "LIMITE_NAMESPACES" -> Categoria.LIMITE_ATINGIDO;
            default -> Categoria.REGRA_VIOLADA;
        };
    }
}
