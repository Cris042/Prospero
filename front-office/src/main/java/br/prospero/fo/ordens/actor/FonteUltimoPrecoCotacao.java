package br.prospero.fo.ordens.actor;

import br.prospero.fo.ordens.dominio.FonteUltimoPreco;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** Apenas o último negócio real da sessão solicitada alimenta o túnel; sintéticos são isolados. */
@ApplicationScoped
public class FonteUltimoPrecoCotacao implements FonteUltimoPreco {
    private final br.prospero.fo.cotacao.dominio.RepositorioCotacao cotacoes;

    @jakarta.inject.Inject
    public FonteUltimoPrecoCotacao(br.prospero.fo.cotacao.dominio.RepositorioCotacao cotacoes) {
        this.cotacoes = cotacoes;
    }
    @Override
    public Optional<BigDecimal> ultimoNegocio(String ticker, LocalDate sessao) {
        return cotacoes.sessao(ticker, sessao).map(br.prospero.fo.cotacao.dominio.SessaoCotacao::ultimoPreco);
    }
}
