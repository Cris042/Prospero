package br.prospero.fo.mercado.jpa;

import br.prospero.fo.mercado.dominio.Ativo;
import br.prospero.fo.mercado.dominio.Negocio;
import br.prospero.fo.mercado.dominio.Oferta;
import br.prospero.fo.mercado.dominio.RepositorioMercado;
import br.prospero.fo.mercado.dominio.SnapshotMercado;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class RepositorioMercadoJpa implements RepositorioMercado {
    private final EntityManager em;
    private final ObjectMapper json;

    @Inject
    public RepositorioMercadoJpa(EntityManager em, ObjectMapper json) {
        this.em = em;
        this.json = json;
    }

    @Override
    @Transactional
    public List<Ativo> listarAtivos() {
        return em.createQuery("from AtivoEntity order by ordem", AtivoEntity.class).getResultList().stream()
                .map(RepositorioMercadoJpa::ativo).toList();
    }

    @Override
    @Transactional
    public Optional<Ativo> buscarAtivo(String ticker) {
        return Optional.ofNullable(em.find(AtivoEntity.class, ticker)).map(RepositorioMercadoJpa::ativo);
    }

    @Override
    @Transactional
    public List<SnapshotMercado> snapshots(UUID namespace) {
        return em.createQuery("from SnapshotMercadoEntity where id.namespace = :n", SnapshotMercadoEntity.class)
                .setParameter("n", namespace).getResultList().stream().map(this::snapshot).toList();
    }

    @Override
    @Transactional
    public Optional<SnapshotMercado> snapshot(UUID namespace, String ticker) {
        SnapshotMercadoId id = new SnapshotMercadoId();
        id.namespace = namespace;
        id.ticker = ticker;
        return Optional.ofNullable(em.find(SnapshotMercadoEntity.class, id)).map(this::snapshot);
    }

    @Override
    @Transactional
    public long substituir(UUID namespace, String ticker, LocalDate sessao, Instant preparadoEm,
                          List<Oferta> ofertas, List<Negocio> negocios) {
        // Upsert atômico: a nova revisão substitui todo o conteúdo em uma única linha.
        Object revisao = em.createNativeQuery("""
                        INSERT INTO snapshot_mercado (namespace, ticker, revisao, sessao, preparado_em, conteudo)
                        VALUES (:namespace, :ticker, 1, :sessao, :preparadoEm, CAST(:conteudo AS jsonb))
                        ON CONFLICT (namespace, ticker) DO UPDATE SET revisao = snapshot_mercado.revisao + 1,
                            sessao = EXCLUDED.sessao, preparado_em = EXCLUDED.preparado_em, conteudo = EXCLUDED.conteudo
                        RETURNING revisao""")
                .setParameter("namespace", namespace).setParameter("ticker", ticker).setParameter("sessao", sessao)
                .setParameter("preparadoEm", OffsetDateTime.ofInstant(preparadoEm, ZoneOffset.UTC))
                .setParameter("conteudo", serializar(ofertas, negocios)).getSingleResult();
        return ((Number) revisao).longValue();
    }

    @Override
    @Transactional
    public boolean namespaceExiste(UUID namespace) {
        return !em.createQuery("select 1 from SnapshotMercadoEntity where id.namespace = :n")
                .setParameter("n", namespace).setMaxResults(1).getResultList().isEmpty();
    }

    @Override
    @Transactional
    public long contarNamespaces() {
        return em.createQuery("select count(distinct id.namespace) from SnapshotMercadoEntity", Long.class)
                .getSingleResult();
    }

    @Override
    @Transactional
    public long remover(UUID namespace) {
        return em.createQuery("delete from SnapshotMercadoEntity where id.namespace = :n")
                .setParameter("n", namespace).executeUpdate();
    }

    private static Ativo ativo(AtivoEntity e) {
        return new Ativo(e.ticker, e.nome, e.precoReferencia, e.tick, e.lote);
    }

    private SnapshotMercado snapshot(SnapshotMercadoEntity e) {
        try {
            ConteudoSnapshot c = json.readValue(e.conteudo, ConteudoSnapshot.class);
            return new SnapshotMercado(e.id.namespace, e.id.ticker, e.revisao, e.sessao, e.preparadoEm,
                    c.ofertas().stream().map(o -> new Oferta(o.lado(), new BigDecimal(o.preco()), o.quantidade())).toList(),
                    c.negocios().stream().map(n -> new Negocio(new BigDecimal(n.preco()), n.quantidade())).toList());
        } catch (JsonProcessingException erro) {
            throw new IllegalStateException("Snapshot persistido ilegível.", erro);
        }
    }

    private String serializar(List<Oferta> ofertas, List<Negocio> negocios) {
        try {
            return json.writeValueAsString(new ConteudoSnapshot(
                    ofertas.stream().map(o -> new ConteudoSnapshot.OfertaJson(o.lado(), o.preco().toPlainString(), o.quantidade())).toList(),
                    negocios.stream().map(n -> new ConteudoSnapshot.NegocioJson(n.preco().toPlainString(), n.quantidade())).toList()));
        } catch (JsonProcessingException erro) {
            throw new IllegalStateException("Falha ao serializar snapshot.", erro);
        }
    }
}
