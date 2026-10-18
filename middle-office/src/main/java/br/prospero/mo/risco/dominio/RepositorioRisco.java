package br.prospero.mo.risco.dominio;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** Porta de persistência do risco; as reservas são atualizações condicionais na linha de controle (HLD-003). */
public interface RepositorioRisco {
    Optional<Decisao> decisao(UUID intencao);

    Optional<ParametrosRisco> parametros(String ticker);

    boolean contaExiste(String conta);

    /** Move valor de disponível para reservado somente se houver saldo; false sem alterar nada. */
    boolean reservarCaixa(String conta, BigDecimal valor);

    /** Idem para a custódia do ativo. */
    boolean reservarCustodia(String conta, String ticker, long quantidade);

    /** Grava a decisão e, se reservada, a reserva correspondente. */
    void registrar(Decisao decisao);
}
