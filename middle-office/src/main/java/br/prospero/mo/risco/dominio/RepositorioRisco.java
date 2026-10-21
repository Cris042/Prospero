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

    /** Idem para a custódia do ativo. */
    boolean reservarCustodia(String conta, String ticker, long quantidade);

    /** Grava a decisão e, se reservada, a reserva correspondente. */
    void registrar(Decisao decisao);

    Optional<Carteira> carteira(String conta);

    /**
     * Preparação local (ADR-012, RF04b): redefine caixa e custódia somente sem reservas vivas; ativos ausentes do
     * mapa ficam com zero. Histórico e deduplicação são preservados.
     */
    void preparar(String conta, BigDecimal saldo, Map<String, Long> custodia);
}
