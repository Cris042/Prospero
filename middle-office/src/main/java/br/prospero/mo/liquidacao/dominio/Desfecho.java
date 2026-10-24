package br.prospero.mo.liquidacao.dominio;

import java.util.Objects;

/** Resultado de um evento de liquidação; pendência e recusa são estados persistidos, não exceções. */
public record Desfecho(Tipo tipo, MotivoRecusa motivo) {
    public enum Tipo { CREDITAR, REPETICAO, AGUARDAR, RECUSAR }

    public static final Desfecho CREDITAR = new Desfecho(Tipo.CREDITAR, null);
    public static final Desfecho REPETICAO = new Desfecho(Tipo.REPETICAO, null);
    public static final Desfecho AGUARDAR = new Desfecho(Tipo.AGUARDAR, null);

    public Desfecho {
        Objects.requireNonNull(tipo);
        if ((tipo == Tipo.RECUSAR) != (motivo != null)) {
            throw new IllegalArgumentException("motivo só na recusa");
        }
    }

    public static Desfecho recusar(MotivoRecusa motivo) {
        return new Desfecho(Tipo.RECUSAR, Objects.requireNonNull(motivo));
    }
}
