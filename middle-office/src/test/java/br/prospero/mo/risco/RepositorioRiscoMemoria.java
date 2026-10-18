package br.prospero.mo.risco;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

/** Alternativa CDI para os testes Quarkus, que não têm banco; mesma semântica do UPDATE condicional. */
@Alternative
@Priority(1)
@ApplicationScoped
public class RepositorioRiscoMemoria extends AvaliarRiscoActorTest.Memoria {
}
