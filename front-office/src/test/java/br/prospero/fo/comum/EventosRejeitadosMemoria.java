package br.prospero.fo.comum;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

/** Testes sem banco: a quarentena fica em memória no lugar do adaptador JPA. */
@Alternative
@Priority(1)
@ApplicationScoped
public class EventosRejeitadosMemoria extends QuarentenaMemoria {
}
