package br.prospero.mo.liquidacao.jpa;

import br.prospero.mo.liquidacao.dominio.CreditoEsperado;
import br.prospero.mo.liquidacao.dominio.EventoLiquidacao;
import br.prospero.mo.liquidacao.dominio.Expectativa;
import br.prospero.mo.liquidacao.dominio.LadoLiquidacao;
import br.prospero.mo.liquidacao.dominio.LiquidacaoAguardando;
import br.prospero.mo.liquidacao.dominio.MotivoRecusa;
import br.prospero.mo.liquidacao.dominio.RecursoCredito;
import br.prospero.mo.liquidacao.dominio.RegrasLiquidacao;
import br.prospero.mo.liquidacao.dominio.RepositorioLiquidacao;
import br.prospero.mo.liquidacao.dominio.ResumoLiquidacao;
import br.prospero.mo.liquidacao.dto.CreditosDTO.CreditoDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.query.NativeQuery;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL nativo sobre as tabelas da V7. O crédito é o mesmo UPDATE por conta do ADR-012 (total e disponível sobem
 * juntos, preservando o CHECK disponivel + reservado = total) sob o lock da conta tomado antes. O advisory lock do id
 * serializa evento e consumo do negócio mesmo quando o evento traz outra conta.
 */
@ApplicationScoped
public class RepositorioLiquidacaoJpa implements RepositorioLiquidacao {
    @Inject Instance<EntityManager> gerenciadores;

    private EntityManager em() {
        return gerenciadores.get();
    }

    private NativeQuery<?> nativa(String sql) {
        return em().createNativeQuery(sql).unwrap(NativeQuery.class);
    }

    /** Igualdade indexável pelo PK (negocio,lado), conta sempre do JWT; consulta limitada a dois lados. */
    @Transactional
    public List<CreditoDTO> consultarCreditos(String conta, String negocio) {
        List<?> linhas = em().createNativeQuery("""
                SELECT negocio, lado, ticker, recurso, coalesce(valor, quantidade), creditada_em IS NOT NULL
                FROM liquidacao_expectativa WHERE negocio = :n AND conta = :c ORDER BY lado
                """).setParameter("n", negocio).setParameter("c", conta).setMaxResults(2).getResultList();
        return linhas.stream().map(l -> {
            Object[] r = (Object[]) l;
            String valor = r[3].equals("CAIXA") ? ((BigDecimal) r[4]).setScale(2).toPlainString()
                    : ((BigDecimal) r[4]).toBigIntegerExact().toString();
            return new CreditoDTO(r[0].toString(), r[1].toString(), r[2].toString(), r[3].toString(), valor, (Boolean) r[5]);
        }).toList();
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void bloquear(String conta, UUID id) {
        em().createNativeQuery("SELECT conta FROM conta_risco WHERE conta = :c FOR UPDATE").setParameter("c", conta)
                .getResultList();
        travarId(id);
    }

    private void travarId(UUID id) {
        em().createNativeQuery("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:k)) trava")
                .setParameter("k", id.getMostSignificantBits() ^ id.getLeastSignificantBits()).getResultList();
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<Expectativa> expectativa(UUID id) {
        List<?> linhas = em().createNativeQuery("""
                SELECT negocio, lado, conta, ticker, valor, quantidade, creditada_em
                FROM liquidacao_expectativa WHERE id = :i FOR UPDATE""").setParameter("i", id).getResultList();
        return linhas.stream().findFirst().map(linha -> {
            Object[] c = (Object[]) linha;
            return new Expectativa(credito(c, 0), c[6] != null);
        });
    }

    /** Colunas negocio, lado, conta, ticker, valor, quantidade a partir de {@code inicio}. */
    private static CreditoEsperado credito(Object[] c, int inicio) {
        LadoLiquidacao lado = LadoLiquidacao.valueOf((String) c[inicio + 1]);
        BigDecimal valor = lado.recurso() == RecursoCredito.CAIXA ? (BigDecimal) c[inicio + 4]
                : BigDecimal.valueOf(((Number) c[inicio + 5]).longValue());
        return new CreditoEsperado((String) c[inicio], lado, (String) c[inicio + 2], (String) c[inicio + 3], valor);
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<String> aplicada(UUID id) {
        return em().createNativeQuery("SELECT assinatura FROM liquidacao_aplicada WHERE id = :i").setParameter("i", id)
                .getResultList().stream().findFirst().map(Object::toString);
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean aguardando(UUID id, String assinatura) {
        return !em().createNativeQuery("SELECT 1 FROM liquidacao_aguardando WHERE id = :i AND assinatura = :h")
                .setParameter("i", id).setParameter("h", assinatura).getResultList().isEmpty();
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public long totalAguardando() {
        // Tabela limitada pelo teto configurado: contagem integral é barata e exata.
        return ((Number) em().createNativeQuery("SELECT count(*) FROM liquidacao_aguardando").getSingleResult()).longValue();
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void creditar(UUID id, String assinatura, CreditoEsperado c) {
        int n = c.recurso() == RecursoCredito.CAIXA
                ? em().createNativeQuery("""
                        UPDATE conta_risco SET total = total + :v, disponivel = disponivel + :v WHERE conta = :c""")
                        .setParameter("v", c.valor()).setParameter("c", c.conta()).executeUpdate()
                : em().createNativeQuery("""
                        UPDATE custodia_risco SET total = total + :q, disponivel = disponivel + :q
                        WHERE conta = :c AND ticker = :t""")
                        .setParameter("q", c.valor().longValueExact()).setParameter("c", c.conta())
                        .setParameter("t", c.ticker()).executeUpdate();
        exigir(n == 1, "recurso do credito ausente");
        exigir(em().createNativeQuery("""
                UPDATE liquidacao_expectativa SET creditada_em = now() WHERE id = :i AND creditada_em IS NULL""")
                .setParameter("i", id).executeUpdate() == 1, "expectativa ja creditada");
        em().createNativeQuery("INSERT INTO liquidacao_aplicada (id, assinatura) VALUES (:i, :h)")
                .setParameter("i", id).setParameter("h", assinatura).executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void aguardar(EventoLiquidacao e, int particao, long deslocamento) {
        CreditoEsperado c = e.credito();
        boolean caixa = c.recurso() == RecursoCredito.CAIXA;
        nativa("""
                INSERT INTO liquidacao_aguardando (id, assinatura, negocio, lado, conta, recurso, ticker, valor, quantidade,
                                                   particao, deslocamento, recebida_em)
                VALUES (:i, :h, :n, :l, :c, :r, :t, :v, :q, :p, :d, now())""")
                .setParameter("i", e.id()).setParameter("h", e.assinatura()).setParameter("n", c.negocio())
                .setParameter("l", c.lado().name()).setParameter("c", c.conta()).setParameter("r", c.recurso().name())
                .setParameter("t", c.ticker()).setParameter("v", caixa ? c.valor() : null, BigDecimal.class)
                .setParameter("q", caixa ? null : c.valor().longValueExact(), Long.class)
                .setParameter("p", particao).setParameter("d", deslocamento).executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void recusar(UUID id, String assinatura, MotivoRecusa motivo, int particao, long deslocamento) {
        nativa("""
                INSERT INTO liquidacao_recusada (evento, assinatura, motivo, particao, deslocamento, recusada_em)
                VALUES (:i, :h, :m, :p, :d, now()) ON CONFLICT (particao, deslocamento) DO NOTHING""")
                .setParameter("i", id, UUID.class).setParameter("h", assinatura, String.class)
                .setParameter("m", motivo.name()).setParameter("p", particao).setParameter("d", deslocamento)
                .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void registrarExpectativas(UUID eventoNegocio, List<CreditoEsperado> esperados) {
        // Ids em ordem fixa: dois advisory locks nunca são tomados em ordens opostas.
        for (CreditoEsperado c : esperados.stream().sorted(Comparator.comparing(CreditoEsperado::id)).toList()) {
            travarId(c.id());
            inserirExpectativa(eventoNegocio, c);
            var resolucao = RegrasLiquidacao.resolver(c, filaAguardando(c.id()));
            resolucao.aplicar().ifPresent(a -> creditar(a.id(), a.assinatura(), c));
            resolucao.recusar().forEach(r -> recusar(r.aguardando().id(), r.aguardando().assinatura(), r.motivo(),
                    r.aguardando().particao(), r.aguardando().deslocamento()));
            em().createNativeQuery("DELETE FROM liquidacao_aguardando WHERE id = :i").setParameter("i", c.id())
                    .executeUpdate();
        }
    }

    private void inserirExpectativa(UUID evento, CreditoEsperado c) {
        boolean caixa = c.recurso() == RecursoCredito.CAIXA;
        nativa("""
                INSERT INTO liquidacao_expectativa (negocio, lado, id, evento, conta, recurso, ticker, valor, quantidade,
                                                    criada_em)
                VALUES (:n, :l, :i, :e, :c, :r, :t, :v, :q, now())""")
                .setParameter("n", c.negocio()).setParameter("l", c.lado().name()).setParameter("i", c.id())
                .setParameter("e", evento).setParameter("c", c.conta()).setParameter("r", c.recurso().name())
                .setParameter("t", c.ticker()).setParameter("v", caixa ? c.valor() : null, BigDecimal.class)
                .setParameter("q", caixa ? null : c.valor().longValueExact(), Long.class).executeUpdate();
    }

    /** Ordem de chegada; o primeiro idêntico é o aplicado. */
    private List<LiquidacaoAguardando> filaAguardando(UUID id) {
        return em().createNativeQuery("""
                        SELECT negocio, lado, conta, ticker, valor, quantidade, assinatura, particao, deslocamento
                        FROM liquidacao_aguardando WHERE id = :i ORDER BY recebida_em, particao, deslocamento FOR UPDATE""")
                .setParameter("i", id).getResultList().stream().map(linha -> {
                    Object[] c = (Object[]) linha;
                    return new LiquidacaoAguardando(id, (String) c[6], credito(c, 0), ((Number) c[7]).intValue(),
                            ((Number) c[8]).longValue());
                }).toList();
    }

    @Override
    @Transactional
    public ResumoLiquidacao resumo() {
        Object[] a = (Object[]) em().createNativeQuery("""
                SELECT count(*), COALESCE(floor(EXTRACT(EPOCH FROM (now() - min(recebida_em)))), 0)
                FROM liquidacao_aguardando""").getSingleResult();
        long pendentes = ((Number) em().createNativeQuery(
                "SELECT count(*) FROM liquidacao_expectativa WHERE creditada_em IS NULL").getSingleResult()).longValue();
        Map<MotivoRecusa, Long> recusadas = new EnumMap<>(MotivoRecusa.class);
        for (MotivoRecusa m : MotivoRecusa.values()) {
            recusadas.put(m, 0L);
        }
        em().createNativeQuery("SELECT motivo, count(*) FROM liquidacao_recusada GROUP BY motivo").getResultList()
                .forEach(linha -> {
                    Object[] c = (Object[]) linha;
                    recusadas.put(MotivoRecusa.valueOf((String) c[0]), ((Number) c[1]).longValue());
                });
        return new ResumoLiquidacao(((Number) a[0]).longValue(), ((Number) a[1]).longValue(), pendentes, recusadas);
    }

    private static void exigir(boolean condicao, String motivo) {
        if (!condicao) {
            throw new IllegalStateException(motivo);
        }
    }
}
