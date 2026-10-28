package br.prospero.fo.atualizacoes.jpa;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class RepositorioRevisoesJpa {
    private final Instance<EntityManager> gerenciadores;
    @Inject
    public RepositorioRevisoesJpa(Instance<EntityManager> gerenciadores) { this.gerenciadores = gerenciadores; }

    @Transactional
    public Map<String, Long> consultar() {
        List<?> linhas = gerenciadores.get().createNativeQuery(
                "SELECT conta,revisao FROM trajetoria_revisao WHERE conta IN (:contas)")
                .setParameter("contas", List.of("CONTA-001", "CONTA-002", "CONTA-003", "CONTA-004", "CONTA-005"))
                .setMaxResults(5).getResultList();
        Map<String, Long> revisoes = new HashMap<>();
        for (Object linha : linhas) {
            Object[] colunas = (Object[]) linha;
            revisoes.put((String) colunas[0], ((Number) colunas[1]).longValue());
        }
        return Map.copyOf(revisoes);
    }
}
