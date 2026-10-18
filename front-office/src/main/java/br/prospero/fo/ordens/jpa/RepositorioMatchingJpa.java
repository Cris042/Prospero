package br.prospero.fo.ordens.jpa;

import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.dominio.Ordem;
import br.prospero.fo.ordens.dominio.MatchingIncoerenteException;
import br.prospero.fo.ordens.dominio.RepositorioMatching;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import br.prospero.fo.ordens.helper.ProjetarMatching;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Progresso dos dois lados e fatos de deduplicação confirmam na mesma transação. */
@ApplicationScoped
public class RepositorioMatchingJpa implements RepositorioMatching {
    private final Instance<EntityManager> gerenciadores;

    @Inject
    public RepositorioMatchingJpa(Instance<EntityManager> gerenciadores) {
        this.gerenciadores = gerenciadores;
    }

    private EntityManager em() {
        return gerenciadores.get();
    }

    @Override
    @Transactional
    public boolean aplicar(EventoMatching e) {
        // Guardião transacional também serializa repetição concorrente do mesmo ID.
        em().createNativeQuery("SELECT pg_advisory_xact_lock(:chave)")
                .setParameter("chave", e.id().getMostSignificantBits()).getSingleResult();
        if (repetida("matching_evento", e.id(), e.assinatura())) {
            return false;
        }
        if (em().createNativeQuery("SELECT 1 FROM ordem WHERE admissao_matching = :admissao AND ticker = :ticker AND data_sessao = :sessao")
                .setParameter("admissao", e.correlacao()).setParameter("ticker", e.ticker())
                .setParameter("sessao", e.sessao()).getResultList().isEmpty()) {
            throw new MatchingIncoerenteException("MATCHING_CORRELACAO");
        }
        if (e.detalhe() instanceof EventoMatching.Negocio n) {
            var agressora = n.agressor() == br.prospero.fo.ordens.dominio.LadoOrdem.COMPRA ? n.compra() : n.venda();
            UUID esperado = UUID.nameUUIDFromBytes(("admissao:" + agressora.intencao()).getBytes(StandardCharsets.UTF_8));
            if (!esperado.equals(e.correlacao())) {
                throw new MatchingIncoerenteException("MATCHING_CORRELACAO_AGRESSOR");
            }
        }
        EventoMatching.Alvo propria = switch (e.detalhe()) {
            case EventoMatching.Aceita a -> a.alvo();
            case EventoMatching.Rejeitada r -> r.alvo();
            default -> null;
        };
        if (propria != null && !e.correlacao().equals(UUID.nameUUIDFromBytes(
                ("admissao:" + propria.intencao()).getBytes(StandardCharsets.UTF_8)))) {
            throw new MatchingIncoerenteException("MATCHING_CORRELACAO_ORDEM");
        }
        List<EventoMatching.Alvo> alvos = alvos(e.detalhe()).stream()
                .sorted(Comparator.comparing(a -> a.ordem().toString())).toList();
        List<OrdemEntity> entidades = new ArrayList<>();
        for (var alvo : alvos) {
            OrdemEntity entidade = em().find(OrdemEntity.class, alvo.ordem(), LockModeType.PESSIMISTIC_WRITE);
            if (entidade == null) {
                throw new MatchingIncoerenteException("MATCHING_ORDEM_AUSENTE");
            }
            entidades.add(entidade);
        }
        if (e.detalhe() instanceof EventoMatching.Negocio n) {
            String assinatura = EnvelopeMatching.assinaturaNegocio(e, n);
            long chave = UUID.nameUUIDFromBytes(n.id().getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
            em().createNativeQuery("SELECT pg_advisory_xact_lock(:chave)").setParameter("chave", chave).getSingleResult();
            if (repetida("matching_negocio", n.id(), assinatura)) {
                registrar(e);
                return false;
            }
        }
        List<?> parametros = em().createNativeQuery("SELECT tick, lote FROM ativo WHERE ticker = :ticker")
                .setParameter("ticker", e.ticker()).getResultList();
        if (parametros.size() != 1) {
            throw new MatchingIncoerenteException("MATCHING_ATIVO_AUSENTE");
        }
        Object[] p = (Object[]) parametros.getFirst();
        List<Ordem> atualizadas = new ArrayList<>();
        for (int i = 0; i < entidades.size(); i++) {
            Ordem antiga = RepositorioOrdensJpa.ordem(entidades.get(i));
            atualizadas.add(ProjetarMatching.aplicar(antiga, e, alvos.get(i), (BigDecimal) p[0], ((Number) p[1]).longValue()));
        }
        for (int i = 0; i < entidades.size(); i++) {
            OrdemEntity entidade = entidades.get(i);
            Ordem nova = atualizadas.get(i);
            entidade.estado = nova.estado().name();
            entidade.quantidadeExecutada = nova.quantidadeExecutada();
            entidade.quantidadeCancelada = nova.quantidadeCancelada();
            entidade.motivoEncerramento = nova.motivoEncerramento();
            entidade.motivoRejeicao = nova.motivoRejeicao() == null ? null : nova.motivoRejeicao().name();
        }
        if (e.detalhe() instanceof EventoMatching.Negocio n) {
            em().createNativeQuery("INSERT INTO matching_negocio (id, assinatura) VALUES (:id, :assinatura)")
                    .setParameter("id", n.id()).setParameter("assinatura", EnvelopeMatching.assinaturaNegocio(e, n)).executeUpdate();
        }
        registrar(e);
        return true;
    }

    private boolean repetida(String tabela, Object id, String assinatura) {
        // Tabela vem apenas dos dois literais internos, nunca de entrada externa.
        List<?> existente = em().createNativeQuery("SELECT assinatura FROM " + tabela + " WHERE id = :id")
                .setParameter("id", id).getResultList();
        if (existente.isEmpty()) {
            return false;
        }
        if (!assinatura.equals(existente.getFirst().toString().trim())) {
            throw new MatchingIncoerenteException("MATCHING_IDENTIDADE_CONFLITANTE");
        }
        return true;
    }

    private void registrar(EventoMatching e) {
        em().createNativeQuery("INSERT INTO matching_evento (id, assinatura) VALUES (:id, :assinatura)")
                .setParameter("id", e.id()).setParameter("assinatura", e.assinatura()).executeUpdate();
    }

    private static List<EventoMatching.Alvo> alvos(EventoMatching.Detalhe detalhe) {
        return switch (detalhe) {
            case EventoMatching.Negocio n -> List.of(n.compra(), n.venda());
            case EventoMatching.Aceita a -> List.of(a.alvo());
            case EventoMatching.Oferta o -> List.of(o.alvo());
            case EventoMatching.Rejeitada r -> List.of(r.alvo());
            case EventoMatching.Encerrada f -> List.of(f.alvo());
        };
    }
}
