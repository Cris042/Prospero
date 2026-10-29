package br.prospero.mo.mensageria;

import br.prospero.mo.mensageria.dominio.EventosRejeitados;
import br.prospero.mo.mensageria.dominio.ItemQuarentena;
import br.prospero.mo.mensageria.dominio.MensagemQuarentena;
import br.prospero.mo.mensageria.dominio.ResolucaoQuarentena;
import br.prospero.mo.mensageria.dominio.MensagemSaida;
import br.prospero.mo.mensageria.dominio.Outbox;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Testes sem banco: outbox e rejeições em memória no lugar dos adaptadores JPA. */
@Alternative
@Priority(1)
@ApplicationScoped
public class MensageriaMemoria implements Outbox, EventosRejeitados {
    private final List<MensagemSaida> mensagens = new ArrayList<>();
    private final QuarentenaMemoria quarentena = new QuarentenaMemoria();

    @Override
    public synchronized void gravar(MensagemSaida mensagem) {
        mensagens.add(mensagem);
    }

    @Override
    public void registrar(MensagemQuarentena mensagem) {
        quarentena.registrar(mensagem);
    }

    @Override
    public List<ItemQuarentena> pendentes(int limite) { return quarentena.pendentes(limite); }

    @Override
    public Optional<ItemQuarentena> buscar(long id) { return quarentena.buscar(id); }

    @Override
    public boolean resolver(long id, ResolucaoQuarentena resolucao) { return quarentena.resolver(id, resolucao); }

    @Override
    public void falhou(long id, String motivo) { quarentena.falhou(id, motivo); }

    public QuarentenaMemoria quarentena() { return quarentena; }

    synchronized List<MensagemSaida> mensagens() {
        return List.copyOf(mensagens);
    }
}
