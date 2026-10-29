package br.prospero.mo.mensageria;

import br.prospero.mo.mensageria.dominio.EventosRejeitados;
import br.prospero.mo.mensageria.dominio.ItemQuarentena;
import br.prospero.mo.mensageria.dominio.MensagemQuarentena;
import br.prospero.mo.mensageria.dominio.ResolucaoQuarentena;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Quarentena em memória com a mesma semântica do adaptador JPA: uma linha por posição, resolução condicional. */
public class QuarentenaMemoria implements EventosRejeitados {
    private final List<ItemQuarentena> itens = new ArrayList<>();

    @Override
    public synchronized void registrar(MensagemQuarentena m) {
        for (int i = 0; i < itens.size(); i++) {
            var atual = itens.get(i);
            var a = atual.mensagem();
            if (a.topico().equals(m.topico()) && a.particao() == m.particao() && a.deslocamento() == m.deslocamento()) {
                itens.set(i, new ItemQuarentena(atual.id(), new MensagemQuarentena(m.topico(), m.particao(), m.deslocamento(),
                        m.chave(), m.traceparent(), m.payload() != null ? m.payload() : a.payload(), m.motivo(),
                        a.tentativas() + m.tentativas()), atual.quarentenadoEm(), null, null));
                return;
            }
        }
        itens.add(new ItemQuarentena(itens.size() + 1L, m, Instant.now(), null, null));
    }

    @Override
    public synchronized List<ItemQuarentena> pendentes(int limite) {
        return itens.stream().filter(ItemQuarentena::pendente).limit(limite).toList();
    }

    @Override
    public synchronized Optional<ItemQuarentena> buscar(long id) {
        return itens.stream().filter(i -> i.id() == id).findFirst();
    }

    @Override
    public synchronized boolean resolver(long id, ResolucaoQuarentena resolucao) {
        var item = buscar(id).filter(ItemQuarentena::pendente);
        item.ifPresent(i -> itens.set(itens.indexOf(i), new ItemQuarentena(i.id(), i.mensagem(), i.quarentenadoEm(),
                resolucao, Instant.now())));
        return item.isPresent();
    }

    @Override
    public synchronized void falhou(long id, String motivo) {
        buscar(id).filter(ItemQuarentena::pendente).ifPresent(i -> {
            var m = i.mensagem();
            itens.set(itens.indexOf(i), new ItemQuarentena(i.id(), new MensagemQuarentena(m.topico(), m.particao(),
                    m.deslocamento(), m.chave(), m.traceparent(), m.payload(), motivo, m.tentativas() + 1),
                    i.quarentenadoEm(), null, null));
        });
    }

    public synchronized void limpar() { itens.clear(); }
}
