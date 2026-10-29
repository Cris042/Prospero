package br.prospero.fo.cotacao.dominio;

import java.util.Optional;

/**
 * Cópia de leitura AP das cotações (ADR-022): pode faltar ou estar alguns milissegundos atrás do PostgreSQL, que
 * continua sendo a fonte da base do túnel. Nenhuma operação lança exceção; indisponibilidade vira ausência.
 */
public interface CacheCotacao {
    Optional<CotacaoEmCache> ler(String ticker);

    /** Grava só se (sessão, revisão) for maior que a guardada; evento repetido ou fora de ordem não regride. */
    void gravar(LivroCotado livro);

    /** Registra "sem cotação" com a menor versão possível: qualquer snapshot real posterior a substitui. */
    void gravarAusente(String ticker);
}
