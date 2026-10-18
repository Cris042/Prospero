package br.prospero.fo.ordens;

import br.prospero.fo.ordens.dominio.FonteUltimoPreco;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Permite exercitar o ramo "último negócio da sessão" e a mudança de base antes da RF06. */
@Alternative
@Priority(1)
@ApplicationScoped
public class FonteUltimoPrecoControlada implements FonteUltimoPreco {
    private final Map<String, BigDecimal> ultimos = new ConcurrentHashMap<>();

    @Override
    public Optional<BigDecimal> ultimoNegocio(String ticker, LocalDate sessao) {
        return Optional.ofNullable(ultimos.get(ticker));
    }

    void definir(String ticker, String preco) {
        ultimos.put(ticker, new BigDecimal(preco));
    }

    void limpar() {
        ultimos.clear();
    }
}
