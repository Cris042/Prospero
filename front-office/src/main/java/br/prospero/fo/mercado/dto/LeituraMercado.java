package br.prospero.fo.mercado.dto;

/**
 * Corpo da leitura de mercado com a origem do dado (ADR-022): REDIS (cópia AP) ou POSTGRES (projeção ou preparação
 * sintética), e a idade em ms desde o último evento aplicado; os cabeçalhos da resposta repetem os dois.
 */
public record LeituraMercado<T>(T corpo, String origem, Long idadeMs) {
    public static final String REDIS = "REDIS";
    public static final String POSTGRES = "POSTGRES";
}
