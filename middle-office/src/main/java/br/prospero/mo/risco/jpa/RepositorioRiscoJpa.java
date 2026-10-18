package br.prospero.mo.risco.jpa;

import br.prospero.mo.risco.dominio.Decisao;
import br.prospero.mo.risco.dominio.Lado;
import br.prospero.mo.risco.dominio.MotivoRejeicao;
import br.prospero.mo.risco.dominio.ParametrosRisco;
import br.prospero.mo.risco.dominio.RepositorioRisco;
import br.prospero.mo.risco.dominio.ResultadoRisco;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.query.NativeQuery;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL nativo: as reservas são UPDATE condicionais na linha de controle, sem ler-e-depois-gravar (HLD-003). Mutações
 * exigem a transação do Actor (MANDATORY): débito sem decisão e outbox nunca confirma sozinho.
 */
@ApplicationScoped
public class RepositorioRiscoJpa implements RepositorioRisco {
    private final EntityManager em;

    @Inject
    public RepositorioRiscoJpa(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional
    public Optional<Decisao> decisao(UUID intencao) {
        List<?> linhas = em.createNativeQuery("""
                        SELECT evento, conta, ticker, lado, resultado, motivo, valor_reservado, quantidade_reservada,
                               versao_parametros, decidida_em
                        FROM decisao_risco WHERE intencao = :i""").setParameter("i", intencao).getResultList();
        return linhas.stream().findFirst().map(linha -> {
            Object[] c = (Object[]) linha;
            return new Decisao(intencao, (UUID) c[0], (String) c[1], (String) c[2], Lado.valueOf((String) c[3]),
                    ResultadoRisco.valueOf((String) c[4]), c[5] == null ? null : MotivoRejeicao.valueOf((String) c[5]),
                    (BigDecimal) c[6], c[7] == null ? null : ((Number) c[7]).longValue(),
                    c[8] == null ? null : ((Number) c[8]).intValue(), instante(c[9]));
        });
    }

    @Override
    @Transactional
    public Optional<ParametrosRisco> parametros(String ticker) {
        List<?> linhas = em.createNativeQuery("""
                        SELECT lote, tick, tunel_percentual, quantidade_maxima, versao FROM ativo_risco WHERE ticker = :t""")
                .setParameter("t", ticker).getResultList();
        return linhas.stream().findFirst().map(linha -> {
            Object[] c = (Object[]) linha;
            return new ParametrosRisco(ticker, ((Number) c[0]).longValue(), (BigDecimal) c[1], (BigDecimal) c[2],
                    ((Number) c[3]).longValue(), ((Number) c[4]).intValue());
        });
    }

    @Override
    @Transactional
    public boolean contaExiste(String conta) {
        return !em.createNativeQuery("SELECT 1 FROM conta_risco WHERE conta = :c").setParameter("c", conta)
                .getResultList().isEmpty();
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean reservarCaixa(String conta, BigDecimal valor) {
        // Suficiência e mutação no mesmo comando: a segunda transação concorrente reavalia o WHERE após o commit.
        return em.createNativeQuery("""
                        UPDATE conta_risco SET disponivel = disponivel - :v, reservado = reservado + :v
                        WHERE conta = :c AND disponivel >= :v""")
                .setParameter("v", valor).setParameter("c", conta).executeUpdate() == 1;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean reservarCustodia(String conta, String ticker, long quantidade) {
        return em.createNativeQuery("""
                        UPDATE custodia_risco SET disponivel = disponivel - :q, reservado = reservado + :q
                        WHERE conta = :c AND ticker = :t AND disponivel >= :q""")
                .setParameter("q", quantidade).setParameter("c", conta).setParameter("t", ticker)
                .executeUpdate() == 1;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void registrar(Decisao d) {
        OffsetDateTime quando = OffsetDateTime.ofInstant(d.decididaEm(), ZoneOffset.UTC);
        // Intenção concorrente duplicada viola a PK e desfaz a transação inteira; a reentrega encontra a decisão.
        nativa("""
                        INSERT INTO decisao_risco (intencao, evento, conta, ticker, lado, resultado, motivo, valor_reservado,
                                                   quantidade_reservada, versao_parametros, decidida_em)
                        VALUES (:i, :e, :c, :t, :l, :r, :m, :v, :q, :p, :d)""")
                .setParameter("i", d.intencao()).setParameter("e", d.evento()).setParameter("c", d.conta())
                .setParameter("t", d.ticker()).setParameter("l", d.lado().name()).setParameter("r", d.resultado().name())
                .setParameter("m", d.motivo() == null ? null : d.motivo().name(), String.class)
                .setParameter("v", d.valorReservado(), BigDecimal.class)
                .setParameter("q", d.quantidadeReservada(), Long.class)
                .setParameter("p", d.versaoParametros(), Integer.class).setParameter("d", quando).executeUpdate();
        if (d.resultado() == ResultadoRisco.RESERVADA) {
            nativa("""
                            INSERT INTO reserva (intencao, conta, ticker, recurso, valor, quantidade, estado, criada_em)
                            VALUES (:i, :c, :t, :r, :v, :q, 'ATIVA', :d)""")
                    .setParameter("i", d.intencao()).setParameter("c", d.conta()).setParameter("t", d.ticker())
                    .setParameter("r", d.valorReservado() != null ? "CAIXA" : "CUSTODIA")
                    .setParameter("v", d.valorReservado(), BigDecimal.class)
                    .setParameter("q", d.quantidadeReservada(), Long.class)
                    .setParameter("d", quando).executeUpdate();
        }
    }

    /** Nulos tipados: sem o tipo, o PostgreSQL recebe o parâmetro nulo como bytea. */
    private NativeQuery<?> nativa(String sql) {
        return em.createNativeQuery(sql).unwrap(NativeQuery.class);
    }

    private static Instant instante(Object valor) {
        return switch (valor) {
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case java.sql.Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("Tipo de data inesperado.");
        };
    }
}
