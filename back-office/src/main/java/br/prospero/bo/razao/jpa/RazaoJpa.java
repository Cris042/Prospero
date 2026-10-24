package br.prospero.bo.razao.jpa;

import br.prospero.bo.posicoes.dominio.ProjecaoIncoerenteException;
import br.prospero.bo.razao.dominio.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Fato, liquidação e partidas participam da transação do Actor RF07; consultas sempre filtradas pela conta. */
@ApplicationScoped
public class RazaoJpa implements ConsultaRazao {
    private static final String PARTICIPACAO = """
            SELECT l.id,f.negocio_id,f.ticker,l.lado,
                   CASE l.lado WHEN 'COMPRADOR' THEN f.ordem_compra ELSE f.ordem_venda END,
                   f.data_sessao,f.data_liquidacao,f.preco,f.quantidade,f.valor,l.estado
            FROM bo_liquidacao l JOIN bo_negocio_fato f ON f.negocio_id=l.negocio_id
            """;
    private final Instance<EntityManager> gerenciadores;
    @Inject public RazaoJpa(Instance<EntityManager> gerenciadores) { this.gerenciadores = gerenciadores; }
    private EntityManager em() { return gerenciadores.get(); }

    /** Tabela pequena e imutável (migração); lida a cada negócio para não manter cópia divergente em memória. */
    public Calendario calendario() {
        Object[] cobertura = (Object[]) em().createNativeQuery("SELECT inicio,fim FROM bo_calendario WHERE id=1")
                .getSingleResult();
        List<?> linhas = em().createNativeQuery("SELECT data FROM bo_feriado").getResultList();
        Set<LocalDate> feriados = linhas.stream().map(RazaoJpa::data).collect(Collectors.toSet());
        return new Calendario(data(cobertura[0]), data(cobertura[1]), feriados);
    }

    public void registrar(FatoNegocio fato, List<TransacaoContabil> transacoes) {
        em().createNativeQuery("""
                INSERT INTO bo_negocio_fato(negocio_id,ticker,data_sessao,data_liquidacao,preco,quantidade,valor,
                                            conta_compra,ordem_compra,conta_venda,ordem_venda)
                VALUES(:n,:t,:s,:l,:p,:q,:v,:cc,:oc,:cv,:ov)
                """).setParameter("n", fato.negocio()).setParameter("t", fato.ticker()).setParameter("s", fato.sessao())
                .setParameter("l", fato.liquidacao()).setParameter("p", fato.preco()).setParameter("q", fato.quantidade())
                .setParameter("v", fato.valor()).setParameter("cc", fato.compra().conta())
                .setParameter("oc", fato.compra().ordem()).setParameter("cv", fato.venda().conta())
                .setParameter("ov", fato.venda().ordem()).executeUpdate();
        int partidas = 0;
        for (TransacaoContabil t : transacoes) {
            em().createNativeQuery("INSERT INTO bo_liquidacao(negocio_id,lado,conta) VALUES(:n,:l,:c)")
                    .setParameter("n", fato.negocio()).setParameter("l", t.lado().name()).setParameter("c", t.conta())
                    .executeUpdate();
            for (Partida p : t.partidas()) inserir(t, p, fato.sessao());
            partidas += t.partidas().size();
        }
        conferir(fato.negocio(), transacoes.size(), partidas);
    }

    private void inserir(TransacaoContabil t, Partida p, LocalDate data) {
        Montante m = p.montante();
        // A coluna é escolhida por unidade (literal interno); nenhum identificador SQL vem da entrada.
        String coluna = m.financeiro() ? "valor" : "quantidade";
        Object quantia = m.financeiro() ? m.valor() : m.valor().longValueExact();
        em().createNativeQuery("INSERT INTO bo_lancamento(transacao,negocio_id,lado,conta,momento,data_referencia,"
                        + "conta_contabil,natureza,unidade," + coluna + ") VALUES(:tr,:n,:l,:c,:m,:d,:cc,:na,:u,:q)")
                .setParameter("tr", t.id()).setParameter("n", t.negocio()).setParameter("l", t.lado().name())
                .setParameter("c", t.conta()).setParameter("m", t.momento().name()).setParameter("d", data)
                .setParameter("cc", p.conta().name()).setParameter("na", p.natureza() == Natureza.DEBITO ? "D" : "C")
                .setParameter("u", m.unidade()).setParameter("q", quantia).executeUpdate();
    }

    /** Invariante conferida no próprio banco antes do commit; violação reverte e bloqueia a partição. */
    private void conferir(String negocio, int transacoes, int partidas) {
        Object[] r = (Object[]) em().createNativeQuery("""
                SELECT (SELECT count(*) FROM bo_liquidacao WHERE negocio_id=:n),
                       (SELECT count(*) FROM bo_lancamento WHERE negocio_id=:n),
                       (SELECT count(*) FROM (SELECT 1 FROM bo_lancamento WHERE negocio_id=:n GROUP BY transacao,unidade
                        HAVING sum(CASE natureza WHEN 'D' THEN coalesce(valor,quantidade)
                                   ELSE -coalesce(valor,quantidade) END)<>0) d)
                """).setParameter("n", negocio).getSingleResult();
        if (numero(r[0]) != transacoes || numero(r[1]) != partidas || numero(r[2]) != 0) {
            throw new ProjecaoIncoerenteException("RAZAO_DESBALANCEADA");
        }
    }

    @Override @Transactional
    public List<ParticipacaoNegocio> negocios(String conta, long antesDe, int limite) {
        return em().createNativeQuery(PARTICIPACAO + " WHERE l.conta=:c AND l.id<:a ORDER BY l.id DESC")
                .setParameter("c", conta).setParameter("a", antesDe).setMaxResults(limite).getResultList().stream()
                .map(o -> participacao((Object[]) o)).toList();
    }

    @Override @Transactional
    public List<ParticipacaoNegocio> negocio(String conta, String negocio) {
        return em().createNativeQuery(PARTICIPACAO + " WHERE l.negocio_id=:n AND l.conta=:c ORDER BY l.id")
                .setParameter("n", negocio).setParameter("c", conta).setMaxResults(2).getResultList().stream()
                .map(o -> participacao((Object[]) o)).toList();
    }

    @Override @Transactional
    public List<LancamentoRazao> lancamentos(String conta, long antesDe, int limite) {
        return em().createNativeQuery("""
                SELECT l.id,l.transacao,l.negocio_id,f.ticker,l.lado,l.momento,l.data_referencia,l.conta_contabil,
                       l.natureza,l.unidade,l.valor,l.quantidade
                FROM bo_lancamento l JOIN bo_negocio_fato f ON f.negocio_id=l.negocio_id
                WHERE l.conta=:c AND l.id<:a ORDER BY l.id DESC
                """).setParameter("c", conta).setParameter("a", antesDe).setMaxResults(limite).getResultList().stream()
                .map(o -> lancamento((Object[]) o)).toList();
    }

    private static ParticipacaoNegocio participacao(Object[] r) {
        return new ParticipacaoNegocio(numero(r[0]), r[1].toString(), r[2].toString(), LadoNegocio.valueOf(r[3].toString()),
                uuid(r[4]), data(r[5]), data(r[6]), (BigDecimal) r[7], numero(r[8]), (BigDecimal) r[9],
                EstadoLiquidacao.valueOf(r[10].toString()));
    }

    private static LancamentoRazao lancamento(Object[] r) {
        String unidade = r[9].toString();
        Montante montante = Montante.BRL.equals(unidade) ? Montante.brl((BigDecimal) r[10]) : Montante.ativo(unidade, numero(r[11]));
        Partida partida = new Partida(ContaContabil.valueOf(r[7].toString()),
                "D".equals(r[8].toString()) ? Natureza.DEBITO : Natureza.CREDITO, montante);
        return new LancamentoRazao(numero(r[0]), uuid(r[1]), r[2].toString(), r[3].toString(),
                LadoNegocio.valueOf(r[4].toString()), Momento.valueOf(r[5].toString()), data(r[6]), partida);
    }

    private static long numero(Object valor) { return ((Number) valor).longValue(); }
    private static UUID uuid(Object valor) { return valor instanceof UUID u ? u : UUID.fromString(valor.toString()); }
    private static LocalDate data(Object valor) { return valor instanceof LocalDate d ? d : LocalDate.parse(valor.toString()); }
}
