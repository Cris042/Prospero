package br.prospero.fo.ordens;

import br.prospero.fo.comum.dominio.MensagemSaida;
import br.prospero.fo.ordens.dominio.EstadoCancelamento;
import br.prospero.fo.ordens.dominio.OperacaoCancelamento;
import br.prospero.fo.ordens.dominio.Ordem;
import br.prospero.fo.ordens.dominio.RepositorioCancelamento;
import br.prospero.fo.ordens.dominio.ResultadoCancelamento;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Mesma semântica do adaptador JPA (uma operação por ordem, só avança por ranking) para os testes REST. */
@Alternative
@Priority(1)
@ApplicationScoped
public class RepositorioCancelamentoMemoria implements RepositorioCancelamento {
    @Inject RepositorioOrdensMemoria ordens;
    private final Map<UUID, OperacaoCancelamento> operacoes = new HashMap<>();
    private final List<MensagemSaida> outbox = new ArrayList<>();

    @Override
    public synchronized Optional<Ordem> travarOrdem(String conta, UUID id) {
        return ordens.buscar(conta, id);
    }

    @Override
    public synchronized Optional<OperacaoCancelamento> buscar(String conta, UUID ordem) {
        return Optional.ofNullable(operacoes.get(ordem)).filter(o -> o.conta().equals(conta));
    }

    @Override
    public synchronized Map<UUID, OperacaoCancelamento> buscar(String conta, Collection<UUID> ids) {
        Map<UUID, OperacaoCancelamento> resultado = new HashMap<>();
        ids.forEach(id -> buscar(conta, id).ifPresent(o -> resultado.put(id, o)));
        return resultado;
    }

    @Override
    public synchronized OperacaoCancelamento registrar(Ordem ordem, EstadoCancelamento estado, MensagemSaida comando,
                                                       Instant agora) {
        var operacao = new OperacaoCancelamento(ordem.id(), ordem.conta(), estado, null, null, agora, agora);
        if (operacoes.putIfAbsent(ordem.id(), operacao) != null) {
            throw new IllegalStateException("uma operação por ordem");
        }
        if (comando != null) {
            outbox.add(comando);
        }
        return operacao;
    }

    @Override
    public synchronized boolean aplicarResultado(ResultadoCancelamento r) {
        var atual = operacoes.get(r.ordem());
        EstadoCancelamento novo = r.confirmado() ? EstadoCancelamento.CONFIRMADO : EstadoCancelamento.RECUSADO;
        if (atual == null || !atual.conta().equals(r.conta()) || !atual.estado().avancaPara(novo)) {
            return false;
        }
        operacoes.put(r.ordem(), new OperacaoCancelamento(r.ordem(), r.conta(), novo, atual.decisao(), r.motivo(),
                atual.solicitadaEm(), r.ocorridoEm() == null ? Instant.now() : r.ocorridoEm()));
        return true;
    }

    @Override
    public double pendenteSegundosMax() {
        return 0;
    }

    synchronized List<MensagemSaida> outbox() {
        return List.copyOf(outbox);
    }

    synchronized void limpar() {
        operacoes.clear();
        outbox.clear();
    }
}
