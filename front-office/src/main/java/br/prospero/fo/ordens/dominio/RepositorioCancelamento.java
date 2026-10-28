package br.prospero.fo.ordens.dominio;

import br.prospero.fo.comum.dominio.MensagemSaida;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Porta de persistência das operações de cancelamento; as transições só avançam (ranking de estado). */
public interface RepositorioCancelamento {
    /** Trava a linha da ordem da conta (serializa PUT, resultado do risco e matching). */
    Optional<Ordem> travarOrdem(String conta, UUID id);

    Optional<OperacaoCancelamento> buscar(String conta, UUID ordem);

    /** Operações das ordens da conta, numa consulta só. */
    Map<UUID, OperacaoCancelamento> buscar(String conta, Collection<UUID> ordens);

    /**
     * Grava a operação. Com {@code comando} (ordem já decidida pelo risco) grava na mesma transação a outbox e o marco
     * CANCELAMENTO_SOLICITADO; sem ele a operação fica AGUARDANDO_RISCO.
     */
    OperacaoCancelamento registrar(Ordem ordem, EstadoCancelamento estado, MensagemSaida comando, Instant agora);

    /** RESULTADO_CANCELAMENTO do MO; false para repetição, regressão ou ordem sem operação. */
    boolean aplicarResultado(ResultadoCancelamento resultado);

    /** Maior idade, em segundos, entre as operações ainda sem resultado final (0 sem pendência). */
    double pendenteSegundosMax();
}
