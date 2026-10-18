package br.prospero.fo.ordens.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Último negócio real do ativo na sessão. Snapshots sintéticos da RF01 nunca são fonte (ADR-009/ADR-011);
 * negócios reais chegam na RF05/RF06.
 */
public interface FonteUltimoPreco {
    Optional<BigDecimal> ultimoNegocio(String ticker, LocalDate sessao);
}
