package br.prospero.fo.ordens.dominio;

import java.util.List;

/** Resultado do registro idempotente: novas ordens, repetição da resposta original ou conflito de chave. */
public sealed interface ResultadoRegistro {
    record Novo(List<Ordem> ordens) implements ResultadoRegistro {
    }

    record Repetido(List<Ordem> ordens) implements ResultadoRegistro {
    }

    record Conflito() implements ResultadoRegistro {
    }
}
