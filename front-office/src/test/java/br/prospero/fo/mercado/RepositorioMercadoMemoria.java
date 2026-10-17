package br.prospero.fo.mercado;

import br.prospero.fo.mercado.dominio.Ativo;
import br.prospero.fo.mercado.dominio.Negocio;
import br.prospero.fo.mercado.dominio.Oferta;
import br.prospero.fo.mercado.dominio.RepositorioMercado;
import br.prospero.fo.mercado.dominio.SnapshotMercado;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Substitui o adaptador JPA nos testes REST; o banco real é coberto pelo smoke e pelo XLSX. */
@Alternative
@Priority(1)
@ApplicationScoped
public class RepositorioMercadoMemoria implements RepositorioMercado {
    static final List<Ativo> ATIVOS = List.of(
            new Ativo("ALFA3", "Alfa Participações", new BigDecimal("10.00"), new BigDecimal("0.01"), 1),
            new Ativo("BETA3", "Beta Indústria", new BigDecimal("20.00"), new BigDecimal("0.01"), 1),
            new Ativo("GAMA3", "Gama Energia", new BigDecimal("30.00"), new BigDecimal("0.01"), 1),
            new Ativo("DELTA3", "Delta Logística", new BigDecimal("40.00"), new BigDecimal("0.01"), 1),
            new Ativo("OMEGA3", "Ômega Varejo", new BigDecimal("50.00"), new BigDecimal("0.01"), 1));
    private final Map<UUID, Map<String, SnapshotMercado>> dados = new ConcurrentHashMap<>();

    @Override
    public List<Ativo> listarAtivos() {
        return ATIVOS;
    }

    @Override
    public Optional<Ativo> buscarAtivo(String ticker) {
        return ATIVOS.stream().filter(a -> a.ticker().equals(ticker)).findFirst();
    }

    @Override
    public List<SnapshotMercado> snapshots(UUID namespace) {
        return List.copyOf(dados.getOrDefault(namespace, Map.of()).values());
    }

    @Override
    public Optional<SnapshotMercado> snapshot(UUID namespace, String ticker) {
        return Optional.ofNullable(dados.getOrDefault(namespace, Map.of()).get(ticker));
    }

    @Override
    public synchronized long substituir(UUID namespace, String ticker, LocalDate sessao, Instant preparadoEm,
                                        List<Oferta> ofertas, List<Negocio> negocios) {
        Map<String, SnapshotMercado> porTicker = dados.computeIfAbsent(namespace, n -> new ConcurrentHashMap<>());
        long revisao = Optional.ofNullable(porTicker.get(ticker)).map(SnapshotMercado::revisao).orElse(0L) + 1;
        porTicker.put(ticker, new SnapshotMercado(namespace, ticker, revisao, sessao, preparadoEm, ofertas, negocios));
        return revisao;
    }

    @Override
    public boolean namespaceExiste(UUID namespace) {
        return dados.containsKey(namespace);
    }

    @Override
    public long contarNamespaces() {
        return dados.size();
    }

    @Override
    public long remover(UUID namespace) {
        Map<String, SnapshotMercado> removidos = dados.remove(namespace);
        return removidos == null ? 0 : removidos.size();
    }

    void limparTudo() {
        dados.clear();
    }
}
