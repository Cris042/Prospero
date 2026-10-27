package br.prospero.me.livro.dominio;

import java.util.List;
import java.util.Objects;

/** Decisão sobre retirada interna, distinta de rejeição de admissão ou confirmação financeira do MO. */
public record ResultadoCancelamentoManual(Situacao situacao, List<SaldoCanceladoManual> terminais) {
    public enum Situacao { APLICADO, SEM_SALDO_VIVO, ALVO_DIVERGENTE, ORIGEM_OCO }

    public ResultadoCancelamentoManual {
        Objects.requireNonNull(situacao);
        terminais = List.copyOf(terminais);
        int esperados = situacao == Situacao.APLICADO ? 1 : 0;
        if (terminais.size() != esperados) {
            throw new IllegalArgumentException("Terminais incompatíveis com a decisão.");
        }
    }
}
