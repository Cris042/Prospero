package br.prospero.bo.posicoes.dto;

import java.util.List;

public record PosicoesResponseDTO(String conta, String base, boolean sincronizado, List<PosicaoDTO> posicoes) {
    public record PosicaoDTO(String ticker, String quantidade, String lado, String precoMedio,
                             String resultadoRealizado, String resultadoNaoRealizado, String cotacao,
                             String sessaoCotacao, boolean cotacaoDefasada, long revisaoCotacao,
                             String atualizadaEm) { }
}
