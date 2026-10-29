package br.prospero.mo.risco.dominio;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Porta de persistência do risco; as reservas são atualizações condicionais na linha de controle (HLD-003). */
public interface RepositorioRisco {
    void bloquearConta(String conta);

    void registrarAdmissao(ComandoRisco comando, Decisao decisao);

    Optional<Decisao> decisao(UUID intencao);

    Optional<ParametrosRisco> parametros(String ticker);

    boolean contaExiste(String conta);

    /** Move valor de disponível para reservado somente se houver saldo; false sem alterar nada. */
    boolean reservarCaixa(String conta, BigDecimal valor);

    /**
     * Limite de posição (ADR-020): soma a quantidade da compra em compra_aberta somente se total + compra_aberta +
     * quantidade não passar do limite da conta no ativo; false sem alterar nada.
     */
    boolean reservarPosicaoCompra(String conta, String ticker, long quantidade);

    /** Desfaz {@link #reservarPosicaoCompra} na mesma transação quando a reserva de caixa falha. */
    void liberarPosicaoCompra(String conta, String ticker, long quantidade);

    /**
     * Venda (ADR-021): reserva a custódia disponível e, se a conta tiver limite de exposição, põe o restante a
     * descoberto com exposição pelo preço de referência e garantia de {@code descoberta × tetoGarantia} em caixa.
     * Cada passo é UPDATE condicional; recusa desfaz os anteriores e não deixa nada reservado.
     */
    ReservaVenda reservarVenda(String conta, String ticker, long quantidade, BigDecimal tetoGarantia);

    /** Grava a parte a descoberto e a garantia na decisão e na reserva recém-registradas. */
    void registrarDescoberto(java.util.UUID intencao, long descoberta, BigDecimal garantia);

    /** Grava a decisão e, se reservada, a reserva correspondente. */
    void registrar(Decisao decisao);

    Optional<Carteira> carteira(String conta);

    /**
     * Preparação local (ADR-012, RF04b): redefine caixa e custódia somente sem reservas vivas; ativos ausentes do
     * mapa ficam com zero. Histórico e deduplicação são preservados.
     */
    void preparar(String conta, BigDecimal saldo, Map<String, Long> custodia);

    /** Expectativa de crédito D+2 ainda não liquidada para a conta (ADR-016); leitura sob o lock da conta. */
    boolean liquidacaoPendente(String conta);
}
