package br.prospero.fo.ordens.helper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Sessão da validade Dia: data civil de America/Sao_Paulo, sem calendário de feriados nesta entrega. */
public final class SessaoNegociacao {
    public static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    private SessaoNegociacao() {
    }

    public static LocalDate data(Instant instante) {
        return LocalDate.ofInstant(instante, FUSO);
    }
}
