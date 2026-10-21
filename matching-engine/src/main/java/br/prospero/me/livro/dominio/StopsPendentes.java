package br.prospero.me.livro.dominio;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Até500 pendentes por ativo; varredura limitada, sempre em chegada, sem prioridade de preço. */
final class StopsPendentes {
    record Pendente(Admissao admissao, long sequencia) { }

    private final NavigableMap<Long, Admissao> ordens = new TreeMap<>();

    static boolean acionavel(Admissao ordem, BigDecimal ultimo) {
        if (ultimo == null) {
            return false;
        }
        int comparacao = ultimo.compareTo(ordem.precoGatilho());
        return ordem.lado() == Lado.COMPRA ? comparacao >= 0 : comparacao <= 0;
    }

    int quantidade() {
        return ordens.size();
    }

    void guardar(Admissao ordem, long sequencia) {
        ordens.put(sequencia, ordem);
    }

    /** Remoção irrevogável: preço posterior não desfaz o acionamento já observado. */
    List<Admissao> retirarAcionados(BigDecimal ultimo) {
        List<Admissao> acionados = new ArrayList<>();
        var entradas = ordens.entrySet().iterator();
        while (entradas.hasNext()) {
            var entrada = entradas.next();
            if (acionavel(entrada.getValue(), ultimo)) {
                acionados.add(entrada.getValue());
                entradas.remove();
            }
        }
        return acionados;
    }

    List<Pendente> retirarTodas() {
        List<Pendente> pendentes = ordens.entrySet().stream()
                .map(entrada -> new Pendente(entrada.getValue(), entrada.getKey())).toList();
        ordens.clear();
        return pendentes;
    }
}
