package br.prospero.fo.cotacao.dominio;

import java.time.LocalDate;
import java.util.Optional;
import br.prospero.fo.ordens.dominio.EventoMatching;

/** Projeção real do mercado: mutações e deduplicação do evento na mesma transação. */
public interface RepositorioCotacao {
    /** Verdadeiro se o evento mudou o mercado; reentrega e fato sem efeito retornam falso. Identidade divergente é incoerente. */
    boolean aplicar(EventoMatching evento);

    Optional<SessaoCotacao> sessao(String ticker, LocalDate sessao);

    /** Sessão mais recente e cinco níveis por lado, lidos no mesmo snapshot SQL. */
    Optional<LivroCotado> livro(String ticker);
}
