package br.prospero.fo.ordens.actor;

import br.prospero.fo.ordens.dominio.FonteUltimoPreco;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** Até a RF05/RF06 não há negócios reais: a base do túnel é sempre o preço de referência do ativo. */
@ApplicationScoped
public class FonteUltimoPrecoSemNegocios implements FonteUltimoPreco {
    @Override
    public Optional<BigDecimal> ultimoNegocio(String ticker, LocalDate sessao) {
        return Optional.empty();
    }
}
