package br.prospero.fo.comum;

import br.prospero.fo.comum.dominio.EventosRejeitados;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

/** Testes sem banco: o consumidor Kafka está desligado e nada é registrado. */
@Alternative
@Priority(1)
@ApplicationScoped
public class EventosRejeitadosMemoria implements EventosRejeitados {
    @Override
    public void registrar(String topico, int particao, long deslocamento, String motivo) {
    }
}
