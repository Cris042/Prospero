package br.prospero.fo.comum.jpa;

import br.prospero.fo.comum.dominio.EventosRejeitados;
import br.prospero.fo.comum.dominio.ItemQuarentena;
import br.prospero.fo.comum.dominio.MensagemQuarentena;
import br.prospero.fo.comum.dominio.ResolucaoQuarentena;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Quarentena em PostgreSQL (ADR-019): uma linha por posição no tópico, retenção de 30 dias pelo relay. */
@ApplicationScoped
public class EventosRejeitadosJpa implements EventosRejeitados {
    private static final String COLUNAS = """
            id, topico, particao, deslocamento, chave, traceparent, payload, motivo, tentativas, quarentenado_em,
            resolucao, resolvido_em""";
    private final EntityManager em;

    @Inject
    public EventosRejeitadosJpa(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void registrar(MensagemQuarentena m) {
        // Reentrega da mesma posição (commit do offset perdido, ou falha depois de um reprocessamento) reabre a linha.
        em.createNativeQuery("""
                        INSERT INTO mensagem_quarentena (topico, particao, deslocamento, chave, traceparent, payload,
                            motivo, tentativas, quarentenado_em)
                        VALUES (:t, :p, :d, :c, :tp, :pl, :m, :n, :r)
                        ON CONFLICT (topico, particao, deslocamento) DO UPDATE SET motivo = EXCLUDED.motivo,
                            tentativas = mensagem_quarentena.tentativas + EXCLUDED.tentativas,
                            payload = COALESCE(EXCLUDED.payload, mensagem_quarentena.payload),
                            resolucao = NULL, resolvido_em = NULL""")
                .setParameter("t", m.topico()).setParameter("p", m.particao()).setParameter("d", m.deslocamento())
                .setParameter("c", m.chave()).setParameter("tp", m.traceparent()).setParameter("pl", m.payload())
                .setParameter("m", m.motivo()).setParameter("n", m.tentativas())
                .setParameter("r", OffsetDateTime.now(ZoneOffset.UTC)).executeUpdate();
    }

    @Override
    @Transactional
    public List<ItemQuarentena> pendentes(int limite) {
        List<?> linhas = em.createNativeQuery("SELECT " + COLUNAS + " FROM mensagem_quarentena "
                + "WHERE resolvido_em IS NULL ORDER BY quarentenado_em, id LIMIT :l").setParameter("l", limite).getResultList();
        return linhas.stream().map(l -> item((Object[]) l)).toList();
    }

    @Override
    @Transactional
    public Optional<ItemQuarentena> buscar(long id) {
        List<?> linhas = em.createNativeQuery("SELECT " + COLUNAS + " FROM mensagem_quarentena WHERE id = :i")
                .setParameter("i", id).getResultList();
        return linhas.stream().findFirst().map(l -> item((Object[]) l));
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean resolver(long id, ResolucaoQuarentena resolucao) {
        return em.createNativeQuery("""
                        UPDATE mensagem_quarentena SET resolucao = :r, resolvido_em = :em
                        WHERE id = :i AND resolvido_em IS NULL""")
                .setParameter("r", resolucao.name()).setParameter("em", OffsetDateTime.now(ZoneOffset.UTC))
                .setParameter("i", id).executeUpdate() == 1;
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void falhou(long id, String motivo) {
        em.createNativeQuery("""
                        UPDATE mensagem_quarentena SET tentativas = tentativas + 1, motivo = :m
                        WHERE id = :i AND resolvido_em IS NULL""")
                .setParameter("m", motivo.length() > MensagemQuarentena.MOTIVO_MAXIMO
                        ? motivo.substring(0, MensagemQuarentena.MOTIVO_MAXIMO) : motivo)
                .setParameter("i", id).executeUpdate();
    }

    private static ItemQuarentena item(Object[] l) {
        var mensagem = new MensagemQuarentena((String) l[1], ((Number) l[2]).intValue(), ((Number) l[3]).longValue(),
                (String) l[4], (String) l[5], (byte[]) l[6], (String) l[7], ((Number) l[8]).intValue());
        return new ItemQuarentena(((Number) l[0]).longValue(), mensagem, instante(l[9]),
                l[10] == null ? null : ResolucaoQuarentena.valueOf((String) l[10]), instante(l[11]));
    }

    private static Instant instante(Object valor) {
        return switch (valor) {
            case null -> null;
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case java.sql.Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("Tipo temporal inesperado: " + valor.getClass().getSimpleName());
        };
    }
}
