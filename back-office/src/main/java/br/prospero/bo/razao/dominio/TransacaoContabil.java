package br.prospero.bo.razao.dominio;

import br.prospero.bo.posicoes.dominio.ProjecaoIncoerenteException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Transação contábil de um lado do negócio; débitos e créditos fecham por unidade. Append-only. */
public record TransacaoContabil(String negocio, LadoNegocio lado, String conta, Momento momento, List<Partida> partidas) {
    public TransacaoContabil {
        if (negocio == null || lado == null || conta == null || momento == null || partidas == null) {
            throw new IllegalArgumentException("Transação incompleta.");
        }
        partidas = List.copyOf(partidas);
        if (partidas.isEmpty() || !balanceada(partidas)) throw new ProjecaoIncoerenteException("RAZAO_DESBALANCEADA");
    }

    /** Identidade determinística por negócio, lado e momento: reentrega não cria outra transação. */
    public UUID id() {
        return UUID.nameUUIDFromBytes(("razao:" + negocio + ":" + lado + ":" + momento).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean balanceada(List<Partida> partidas) {
        Map<String, BigDecimal> saldo = new TreeMap<>();
        for (Partida p : partidas) {
            BigDecimal valor = p.natureza() == Natureza.DEBITO ? p.montante().valor() : p.montante().valor().negate();
            saldo.merge(p.montante().unidade(), valor, BigDecimal::add);
        }
        return saldo.values().stream().allMatch(v -> v.signum() == 0);
    }
}
