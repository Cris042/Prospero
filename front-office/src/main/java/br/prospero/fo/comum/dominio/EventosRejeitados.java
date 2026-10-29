package br.prospero.fo.comum.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Porta da quarentena (ADR-019). {@link #registrar} é idempotente pela posição no tópico e roda antes do commit do
 * offset: se o commit se perder, a reentrega cai na mesma linha.
 */
public interface EventosRejeitados {
    void registrar(MensagemQuarentena mensagem);

    List<ItemQuarentena> pendentes(int limite);

    Optional<ItemQuarentena> buscar(long id);

    /** Fecha a linha só se ainda estiver pendente; devolve false quando outra chamada já resolveu. */
    boolean resolver(long id, ResolucaoQuarentena resolucao);

    /** Reprocessamento falhou de novo: conta a tentativa e guarda o motivo novo, mantendo a linha pendente. */
    void falhou(long id, String motivo);
}
