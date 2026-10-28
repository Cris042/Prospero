package br.prospero.mo.cancelamento.jpa;

import br.prospero.mo.cancelamento.dominio.EstadoCancelamento;
import br.prospero.mo.cancelamento.dominio.MotivoRecusa;
import br.prospero.mo.cancelamento.dominio.OperacaoCancelamento;
import br.prospero.mo.cancelamento.dominio.RepositorioCancelamento;
import br.prospero.mo.cancelamento.dominio.ReservaAlvo;
import br.prospero.mo.risco.helper.EnvelopeAdmissao;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.query.NativeQuery;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL nativo da coordenação; toda operação exige a transação do caso de uso (MANDATORY). */
@ApplicationScoped
public class RepositorioCancelamentoJpa implements RepositorioCancelamento {
    private static final String COLUNAS = "ordem,intencao,admissao,conta,ticker,data_sessao,solicitacao,comando,assinatura,"
            + "estado,terminal,decisao,motivo,traceparent";
    private final Instance<EntityManager> gerenciadores;

    /** Resolução tardia, como no repositório de matching: sem banco ativo o bean existe, mas ninguém o usa. */
    @Inject
    public RepositorioCancelamentoJpa(Instance<EntityManager> gerenciadores) {
        this.gerenciadores = gerenciadores;
    }

    private EntityManager em() {
        return gerenciadores.get();
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<OperacaoCancelamento> buscar(UUID ordem) {
        return operacao("SELECT " + COLUNAS + " FROM cancelamento_operacao WHERE ordem=:o", ordem);
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<OperacaoCancelamento> travar(UUID ordem) {
        return operacao("SELECT " + COLUNAS + " FROM cancelamento_operacao WHERE ordem=:o FOR UPDATE", ordem);
    }

    private Optional<OperacaoCancelamento> operacao(String sql, UUID ordem) {
        return em().createNativeQuery(sql).setParameter("o", ordem).getResultList().stream()
                .map(linha -> mapear((Object[]) linha)).findFirst();
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<ReservaAlvo> reservaComprovada(UUID intencao) {
        // Sem FOR UPDATE de propósito: a conta já está travada pelo chamador e toda mudança de reserva passa por esse lock.
        // O recibo e a admissão derivada da intenção provam a publicação; reserva anterior ao V8 não tem nenhum dos dois.
        return em().createNativeQuery("""
                        SELECT r.ordem, r.conta, r.ticker, r.data_sessao, r.oco, r.estado FROM reserva r
                        JOIN admissao_publicacao a ON a.admissao = r.admissao
                        WHERE r.intencao=:i AND r.admissao=:a AND r.data_sessao IS NOT NULL""")
                .setParameter("i", intencao).setParameter("a", EnvelopeAdmissao.id(intencao)).getResultList().stream()
                .map(linha -> {
                    Object[] c = (Object[]) linha;
                    return new ReservaAlvo((UUID) c[0], (String) c[1], (String) c[2], data(c[3]), (Boolean) c[4],
                            "ENCERRADA".equals(c[5]));
                }).findFirst();
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean travarReciboConfirmado(UUID admissao) {
        List<?> linhas = em().createNativeQuery("SELECT confirmada_em IS NOT NULL FROM admissao_publicacao WHERE admissao=:a FOR UPDATE")
                .setParameter("a", admissao).getResultList();
        if (linhas.size() != 1) {
            throw new IllegalStateException("Recibo da admissão ausente.");
        }
        return (Boolean) linhas.getFirst();
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void inserir(OperacaoCancelamento o) {
        em().createNativeQuery("""
                        INSERT INTO cancelamento_operacao(ordem,intencao,admissao,conta,ticker,data_sessao,solicitacao,comando,
                        assinatura,estado,decisao,motivo,traceparent,decidida_em)
                        VALUES(:o,:i,:a,:c,:t,:s,:sol,:cmd,:ass,:e,:dec,:m,:tp,CASE WHEN CAST(:rec AS boolean) THEN now() END)""")
                .unwrap(NativeQuery.class).setParameter("o", o.ordem()).setParameter("i", o.intencao())
                .setParameter("a", o.admissao()).setParameter("c", o.conta()).setParameter("t", o.ticker())
                .setParameter("s", o.dataSessao()).setParameter("sol", o.solicitacao()).setParameter("cmd", o.comando())
                .setParameter("ass", o.assinatura()).setParameter("e", o.estado().name())
                .setParameter("dec", o.decisao(), String.class)
                .setParameter("m", o.motivo() == null ? null : o.motivo().name(), String.class)
                .setParameter("tp", o.traceparent(), String.class)
                .setParameter("rec", o.estado() == EstadoCancelamento.RECUSADO).executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public List<OperacaoCancelamento> promoverAguardando(Collection<UUID> admissoes) {
        if (admissoes.isEmpty()) {
            return List.of();
        }
        List<OperacaoCancelamento> promovidas = new ArrayList<>();
        for (Object linha : em().createNativeQuery("SELECT " + COLUNAS + """
                         FROM cancelamento_operacao WHERE admissao IN (:ids) AND estado='AGUARDANDO_ACK'
                        ORDER BY ordem FOR UPDATE""").setParameter("ids", new ArrayList<>(admissoes)).getResultList()) {
            OperacaoCancelamento aguardando = mapear((Object[]) linha);
            exatamenteUma(em().createNativeQuery("UPDATE cancelamento_operacao SET estado='COMANDO_EMITIDO' WHERE ordem=:o")
                    .setParameter("o", aguardando.ordem()).executeUpdate());
            promovidas.add(aguardando.comEstado(EstadoCancelamento.COMANDO_EMITIDO));
        }
        return promovidas;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void registrarTerminal(UUID ordem, UUID terminal) {
        exatamenteUma(em().createNativeQuery("""
                        UPDATE cancelamento_operacao SET estado='TERMINAL_APLICADO', terminal=:t
                        WHERE ordem=:o AND estado='COMANDO_EMITIDO'""")
                .setParameter("t", terminal).setParameter("o", ordem).executeUpdate());
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void concluir(OperacaoCancelamento c) {
        exatamenteUma(em().createNativeQuery("""
                        UPDATE cancelamento_operacao SET estado=:e, decisao=:d, motivo=:m, decidida_em=now()
                        WHERE ordem=:o AND estado IN ('COMANDO_EMITIDO','TERMINAL_APLICADO')""")
                .unwrap(NativeQuery.class).setParameter("e", c.estado().name()).setParameter("d", c.decisao(), String.class)
                .setParameter("m", c.motivo() == null ? null : c.motivo().name(), String.class)
                .setParameter("o", c.ordem()).executeUpdate());
    }

    private static void exatamenteUma(int linhas) {
        if (linhas != 1) {
            throw new IllegalStateException("Transição de cancelamento sem a linha esperada.");
        }
    }

    private static OperacaoCancelamento mapear(Object[] c) {
        return new OperacaoCancelamento((UUID) c[0], (UUID) c[1], (UUID) c[2], (String) c[3], (String) c[4], data(c[5]),
                (UUID) c[6], (UUID) c[7], (String) c[8], EstadoCancelamento.valueOf((String) c[9]), (UUID) c[10],
                (String) c[11], c[12] == null ? null : MotivoRecusa.valueOf((String) c[12]), (String) c[13]);
    }

    private static LocalDate data(Object valor) {
        return valor instanceof LocalDate data ? data : ((java.sql.Date) valor).toLocalDate();
    }
}
