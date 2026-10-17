package br.prospero.fo.mercado.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de persistência da projeção de leitura do front office. */
public interface RepositorioMercado {
    List<Ativo> listarAtivos();

    Optional<Ativo> buscarAtivo(String ticker);

    List<SnapshotMercado> snapshots(UUID namespace);

    Optional<SnapshotMercado> snapshot(UUID namespace, String ticker);

    /** Substitui integralmente o snapshot e devolve a nova revisão. */
    long substituir(UUID namespace, String ticker, LocalDate sessao, Instant preparadoEm,
                    List<Oferta> ofertas, List<Negocio> negocios);

    boolean namespaceExiste(UUID namespace);

    long contarNamespaces();

    long remover(UUID namespace);
}
