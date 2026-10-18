package br.prospero.mo.mensageria;

import br.prospero.mo.mensageria.dominio.EventosRejeitados;
import br.prospero.mo.mensageria.dominio.MensagemSaida;
import br.prospero.mo.mensageria.dominio.Outbox;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.ArrayList;
import java.util.List;

/** Testes sem banco: outbox e rejeições em memória no lugar dos adaptadores JPA. */
@Alternative
@Priority(1)
@ApplicationScoped
public class MensageriaMemoria implements Outbox, EventosRejeitados {
    private final List<MensagemSaida> mensagens = new ArrayList<>();

    @Override
    public synchronized void gravar(MensagemSaida mensagem) {
        mensagens.add(mensagem);
    }

    @Override
    public void registrar(String topico, int particao, long deslocamento, String motivo) {
    }

    synchronized List<MensagemSaida> mensagens() {
        return List.copyOf(mensagens);
    }
}
