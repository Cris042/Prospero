package br.prospero.mo.sessao.dominio;

import br.prospero.mo.mensageria.dominio.MensagemSaida;

/** Comando de encerramento na outbox; idempotente pelo id determinístico do ativo/data. */
public interface RepositorioSessao {
    /** true se gravou agora; false se o mesmo encerramento já estava registrado. */
    boolean registrar(MensagemSaida comando);
}
