package br.prospero.fo.ordens.dominio;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de persistência das ordens; idempotência e limite diário são garantidos no banco. */
public interface RepositorioOrdens {
    boolean contaExiste(String conta);

    Optional<ParametrosAtivo> parametros(String ticker);

    /**
     * Repetição ou conflito de uma chave ainda válida, sem gravar nada. Consultado antes das regras que
     * dependem do mercado, para que a repetição de um envio aceito devolva sempre a resposta original.
     */
    Optional<ResultadoRegistro> repeticao(String conta, String chave, String hash, Instant agora);

    /**
     * Na mesma transação: reserva a chave (conta, chave), confere o hash em caso de repetição,
     * aplica o limite diário de ordens e grava as ordens.
     */
    ResultadoRegistro registrar(String conta, String chave, String hash, Instant agora, List<Ordem> ordens);

    Optional<Ordem> buscar(String conta, UUID id);

    List<Ordem> buscarGrupoOco(String conta, UUID grupoOco);

    /** Página por recebimento decrescente; o cursor é exclusivo. */
    List<Ordem> listar(String conta, Cursor cursor, int limite);

    record Cursor(Instant recebidaEm, UUID id) {
    }
}
