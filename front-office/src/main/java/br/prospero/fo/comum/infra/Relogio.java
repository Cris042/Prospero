package br.prospero.fo.comum.infra;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.time.Clock;

/** Relógio injetável (ADR-002): testes substituem por relógio fixo. */
@ApplicationScoped
public class Relogio {
    @Produces
    @ApplicationScoped
    Clock relogio() {
        return Clock.systemUTC();
    }
}
