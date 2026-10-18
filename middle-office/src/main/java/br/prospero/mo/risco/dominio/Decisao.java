package br.prospero.mo.risco.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Decisão persistida por intenção; reentregas devolvem esta decisão sem reavaliar. */
public record Decisao(UUID intencao, UUID evento, String conta, String ticker, Lado lado, ResultadoRisco resultado,
                      MotivoRejeicao motivo, BigDecimal valorReservado, Long quantidadeReservada,
                      Integer versaoParametros, Instant decididaEm) {
    public Decisao {
        Objects.requireNonNull(intencao);
        Objects.requireNonNull(evento);
        Objects.requireNonNull(conta);
        Objects.requireNonNull(ticker);
        Objects.requireNonNull(lado);
        Objects.requireNonNull(resultado);
        Objects.requireNonNull(decididaEm);
        if ((resultado == ResultadoRisco.REJEITADA) != (motivo != null)) {
            throw new IllegalArgumentException("Motivo existe somente na rejeição.");
        }
    }

    public static Decisao rejeitada(ComandoRisco c, MotivoRejeicao motivo, Integer versao, Instant agora) {
        return new Decisao(c.intencao(), c.evento(), c.conta(), c.ticker(), c.lado(), ResultadoRisco.REJEITADA, motivo,
                null, null, versao, agora);
    }

    public static Decisao reservada(ComandoRisco c, Exigencia exigencia, int versao, Instant agora) {
        return switch (exigencia) {
            case Exigencia.Caixa caixa -> new Decisao(c.intencao(), c.evento(), c.conta(), c.ticker(), c.lado(),
                    ResultadoRisco.RESERVADA, null, caixa.valor(), null, versao, agora);
            case Exigencia.Custodia custodia -> new Decisao(c.intencao(), c.evento(), c.conta(), c.ticker(), c.lado(),
                    ResultadoRisco.RESERVADA, null, null, custodia.quantidade(), versao, agora);
            case Exigencia.Recusa recusa -> rejeitada(c, recusa.motivo(), versao, agora);
        };
    }
}
