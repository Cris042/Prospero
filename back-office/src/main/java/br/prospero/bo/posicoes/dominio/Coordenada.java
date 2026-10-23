package br.prospero.bo.posicoes.dominio;

/** Identidade de transporte separada da identidade econômica do negócio. */
public record Coordenada(String topicoId, int particao, long offset, String assinatura) {
    public Coordenada {
        if (topicoId == null || !topicoId.matches("[A-Za-z0-9_-]{22}") || particao < 0 || particao > 2
                || offset < 0 || assinatura == null || !assinatura.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Coordenada inválida.");
        }
    }
}
