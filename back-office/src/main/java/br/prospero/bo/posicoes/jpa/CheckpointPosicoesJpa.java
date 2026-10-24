package br.prospero.bo.posicoes.jpa;

import br.prospero.bo.posicoes.dominio.*;
import br.prospero.bo.posicoes.helper.EnvelopeMatching;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.List;

/** Progresso e evidência de transporte participam da transação financeira do Actor. */
@ApplicationScoped
public class CheckpointPosicoesJpa {
    /** RF08b: negócio RF07 sem fato econômico; anti-join executado só no bootstrap. */
    public static final String SEM_ORIGEM = "SELECT EXISTS(SELECT 1 FROM bo_negocio n WHERE NOT EXISTS("
            + "SELECT 1 FROM bo_negocio_fato f WHERE f.negocio_id=n.id))";
    private final Instance<EntityManager> gerenciadores;
    @Inject public CheckpointPosicoesJpa(Instance<EntityManager> gerenciadores) { this.gerenciadores = gerenciadores; }
    private EntityManager em() { return gerenciadores.get(); }

    @Transactional
    public void iniciar(String topicoId) {
        if (topicoId == null || !topicoId.matches("[A-Za-z0-9_-]{22}")) throw erro("TOPICO_INVALIDO");
        em().createNativeQuery("SELECT pg_advisory_xact_lock(71407001)").getSingleResult();
        // ADR-016: volume da RF07 sem fatos econômicos não é completado nem reiniciado; exige recriar bo-dados.
        if ((Boolean) em().createNativeQuery(SEM_ORIGEM).getSingleResult()) throw erro("RAZAO_SEM_ORIGEM");
        List<?> log = em().createNativeQuery("SELECT topico_id FROM bo_log WHERE id=1").getResultList();
        long particoes = ((Number) em().createNativeQuery("SELECT count(*) FROM bo_checkpoint").getSingleResult()).longValue();
        if (log.isEmpty()) {
            boolean anterior = (Boolean) em().createNativeQuery("SELECT EXISTS(SELECT 1 FROM bo_evento)").getSingleResult();
            if (particoes != 0 || anterior) throw erro("BOOTSTRAP_INCOMPLETO");
            em().createNativeQuery("INSERT INTO bo_log(id,topico_id) VALUES(1,:id)").setParameter("id", topicoId).executeUpdate();
            em().createNativeQuery("INSERT INTO bo_checkpoint(particao,proximo_offset) VALUES(0,0),(1,0),(2,0)").executeUpdate();
            return;
        }
        if (!topicoId.equals(log.getFirst()) || particoes != 3) throw erro("LOG_DIVERGENTE");
    }

    public boolean receber(Coordenada c, EventoMatching e) {
        Object[] linha = (Object[]) em().createNativeQuery("""
                SELECT c.proximo_offset,l.topico_id FROM bo_checkpoint c CROSS JOIN bo_log l
                WHERE c.particao=:p AND l.id=1 FOR UPDATE OF c
                """).setParameter("p", c.particao()).getSingleResult();
        if (!c.topicoId().equals(linha[1])) throw erro("LOG_DIVERGENTE");
        long proximo = ((Number) linha[0]).longValue();
        if (c.offset() < proximo) {
            conferirCoordenada(c);
            return false;
        }
        if (c.offset() != proximo) throw erro("LACUNA_NO_LOG");
        boolean novo = novo("bo_evento", e.id(), e.assinatura());
        if (e.detalhe() instanceof EventoMatching.Negocio n) {
            // Mesmo negócio em novo envelope é inócuo, mas conteúdo divergente nunca é ignorado.
            boolean negocioNovo = novo("bo_negocio", n.id(), EnvelopeMatching.assinaturaNegocio(e, n));
            if (!novo && negocioNovo) throw erro("EVENTO_SEM_NEGOCIO");
            novo &= negocioNovo;
        }
        em().createNativeQuery("INSERT INTO bo_transporte(particao,registro_offset,assinatura) VALUES(:p,:o,:a)")
                .setParameter("p", c.particao()).setParameter("o", c.offset()).setParameter("a", c.assinatura()).executeUpdate();
        em().createNativeQuery("UPDATE bo_checkpoint SET proximo_offset=:o WHERE particao=:p")
                .setParameter("o", Math.addExact(c.offset(), 1)).setParameter("p", c.particao()).executeUpdate();
        return novo;
    }

    private boolean novo(String tabela, Object id, String assinatura) {
        // A tabela é somente um dos dois literais internos acima; nenhum identificador SQL vem da entrada.
        int inseridos = em().createNativeQuery("INSERT INTO " + tabela + "(id,assinatura) VALUES(:id,:a) ON CONFLICT DO NOTHING")
                .setParameter("id", id).setParameter("a", assinatura).executeUpdate();
        String anterior = em().createNativeQuery("SELECT assinatura FROM " + tabela + " WHERE id=:id")
                .setParameter("id", id).getSingleResult().toString().trim();
        if (!assinatura.equals(anterior)) throw erro("IDENTIDADE_CONFLITANTE");
        return inseridos == 1;
    }

    private void conferirCoordenada(Coordenada c) {
        List<?> linhas = em().createNativeQuery("SELECT assinatura FROM bo_transporte WHERE particao=:p AND registro_offset=:o")
                .setParameter("p", c.particao()).setParameter("o", c.offset()).getResultList();
        if (linhas.size() != 1 || !c.assinatura().equals(linhas.getFirst().toString().trim())) throw erro("COORDENADA_DIVERGENTE");
    }

    @Transactional
    public long proximo(int particao) {
        return ((Number) em().createNativeQuery("SELECT proximo_offset FROM bo_checkpoint WHERE particao=:p")
                .setParameter("p", particao).getSingleResult()).longValue();
    }

    private static ProjecaoIncoerenteException erro(String motivo) { return new ProjecaoIncoerenteException(motivo); }
}
