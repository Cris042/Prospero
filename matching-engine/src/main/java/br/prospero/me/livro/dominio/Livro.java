package br.prospero.me.livro.dominio;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/** Livro de um ativo: compras por preço decrescente, vendas crescente, FIFO por nível e índice por ordem. */
final class Livro {
    private final NavigableMap<BigDecimal, ArrayDeque<OfertaViva>> compras = new TreeMap<>(Collections.reverseOrder());
    private final NavigableMap<BigDecimal, ArrayDeque<OfertaViva>> vendas = new TreeMap<>();
    private final Map<UUID, OfertaViva> porOrdem = new HashMap<>();

    NavigableMap<BigDecimal, ArrayDeque<OfertaViva>> lado(Lado lado) {
        return lado == Lado.COMPRA ? compras : vendas;
    }

    /** Melhor oferta do lado (cabeça da fila do melhor preço), ou null. */
    OfertaViva melhor(Lado lado) {
        var nivel = lado(lado).firstEntry();
        return nivel == null ? null : nivel.getValue().peekFirst();
    }

    void repousar(OfertaViva oferta) {
        lado(oferta.lado).computeIfAbsent(oferta.preco, p -> new ArrayDeque<>()).addLast(oferta);
        porOrdem.put(oferta.ordem, oferta);
    }

    /** Remove a cabeça do melhor nível do lado, que deve ser a oferta informada. */
    void removerCabeca(OfertaViva oferta) {
        var niveis = lado(oferta.lado);
        ArrayDeque<OfertaViva> fila = niveis.get(oferta.preco);
        if (fila == null || fila.pollFirst() != oferta) {
            throw new IllegalStateException("Oferta fora da cabeça do nível.");
        }
        if (fila.isEmpty()) {
            niveis.remove(oferta.preco);
        }
        porOrdem.remove(oferta.ordem);
    }

    /** Retira uma oferta de qualquer posição, preservando o FIFO das demais; null se não estiver no livro. */
    OfertaViva remover(UUID ordem) {
        OfertaViva oferta = porOrdem.remove(ordem);
        if (oferta == null) {
            return null;
        }
        var niveis = lado(oferta.lado);
        ArrayDeque<OfertaViva> fila = niveis.get(oferta.preco);
        fila.remove(oferta);
        if (fila.isEmpty()) {
            niveis.remove(oferta.preco);
        }
        return oferta;
    }

    boolean contem(UUID ordem) {
        return porOrdem.containsKey(ordem);
    }

    /** Retira ambos os lados e índices em ordem global de chegada, independente do preço e do HashMap. */
    List<OfertaViva> retirarTodas() {
        List<OfertaViva> ofertas = porOrdem.values().stream()
                .sorted(Comparator.comparingLong(o -> o.sequencia)).toList();
        compras.clear();
        vendas.clear();
        porOrdem.clear();
        return ofertas;
    }
}
