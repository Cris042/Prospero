package br.prospero.mo.sessao;

import br.prospero.mo.mensageria.dominio.MensagemSaida;
import br.prospero.mo.sessao.dominio.RepositorioSessao;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Testes sem banco: comandos de encerramento em memória, idempotentes pelo id. */
@Alternative
@Priority(1)
@ApplicationScoped
public class RepositorioSessaoMemoria implements RepositorioSessao {
    private final Map<UUID, MensagemSaida> comandos = new LinkedHashMap<>();

    @Override
    public synchronized boolean registrar(MensagemSaida comando) {
        return comandos.putIfAbsent(comando.id(), comando) == null;
    }

    public synchronized List<MensagemSaida> comandos() {
        return List.copyOf(comandos.values());
    }
}
