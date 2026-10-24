package br.prospero.bo.liquidacao.jpa;

import br.prospero.bo.liquidacao.dominio.MovimentoInvalidoException;
import br.prospero.bo.liquidacao.dto.MovimentoResultadoDTO;
import br.prospero.bo.liquidacao.helper.EventoCredito;
import br.prospero.bo.liquidacao.helper.LimiteAvanco;
import br.prospero.bo.posicoes.dominio.ProjecaoIncoerenteException;
import br.prospero.bo.razao.dominio.*;
import br.prospero.bo.razao.jpa.RazaoJpa;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static br.prospero.bo.razao.dominio.ContaContabil.*;
import static br.prospero.bo.razao.dominio.Natureza.*;

/** Data, partidas D2, estado e outbox são confirmados localmente; nenhum envio Kafka dentro da liquidação. */
@ApplicationScoped
public class LiquidacaoJpa {
    public static final int LOTE = 50;
    private final Instance<EntityManager> gerenciadores;
    private final RazaoJpa razao;
    @Inject public LiquidacaoJpa(Instance<EntityManager> gerenciadores, RazaoJpa razao) {
        this.gerenciadores = gerenciadores;
        this.razao = razao;
    }
    private EntityManager em() { return gerenciadores.get(); }

    @Transactional
    public LocalDate atual() {
        em().createNativeQuery("INSERT INTO bo_data_movimento(id,data) VALUES(1,:d) ON CONFLICT DO NOTHING")
                .setParameter("d", LocalDate.now(ZoneId.of("America/Sao_Paulo"))).executeUpdate();
        return LocalDate.parse(em().createNativeQuery("SELECT data FROM bo_data_movimento WHERE id=1 FOR UPDATE")
                .getSingleResult().toString());
    }

    @Transactional
    public MovimentoResultadoDTO avancar(LocalDate alvo, String sub, String jti, LocalDate hoje) {
        if (sub == null || jti == null || !sub.matches("[A-Za-z0-9@._:-]{1,100}")
                || !jti.matches("[A-Za-z0-9._:-]{1,100}")) throw new MovimentoInvalidoException(403, "ACESSO_NEGADO");
        em().createNativeQuery("INSERT INTO bo_data_movimento(id,data) VALUES(1,:d) ON CONFLICT DO NOTHING")
                .setParameter("d", hoje).executeUpdate();
        LocalDate anterior = atual();
        exigirDisponivel();
        LimiteAvanco.validar(razao.calendario(), anterior, alvo);
        if (alvo.equals(anterior)) return new MovimentoResultadoDTO(anterior.toString(), 0);
        int alteradas = em().createNativeQuery("UPDATE bo_data_movimento SET data=:d WHERE id=1 AND data=:a")
                .setParameter("d", alvo).setParameter("a", anterior).executeUpdate();
        if (alteradas != 1) throw new MovimentoInvalidoException(409, "DATA_CONCORRENTE");
        int liquidadas = liquidarLote(alvo);
        em().createNativeQuery("INSERT INTO bo_movimento_auditoria(sub,jti,de,para,liquidacoes) VALUES(:s,:j,:d,:p,:l)")
                .setParameter("s", sub).setParameter("j", jti).setParameter("d", anterior)
                .setParameter("p", alvo).setParameter("l", liquidadas).executeUpdate();
        return new MovimentoResultadoDTO(alvo.toString(), liquidadas);
    }

    @Transactional
    public int processarLote() {
        LocalDate data = atual();
        exigirDisponivel();
        return liquidarLote(data);
    }

    private int liquidarLote(LocalDate data) {
        List<?> linhas = em().createNativeQuery("""
                SELECT l.id,l.negocio_id,l.lado,l.conta,f.ticker,f.quantidade,f.valor,f.data_liquidacao,o.evento
                FROM bo_liquidacao l JOIN bo_negocio_fato f ON f.negocio_id=l.negocio_id
                LEFT JOIN bo_negocio_origem o ON o.negocio_id=f.negocio_id
                WHERE l.estado='PENDENTE' AND f.data_liquidacao<=:d
                ORDER BY f.data_liquidacao,l.id LIMIT :n FOR UPDATE OF l
                """).setParameter("d", data).setParameter("n", LOTE).getResultList();
        for (Object linha : linhas) liquidar((Object[]) linha);
        return linhas.size();
    }

    private void liquidar(Object[] r) {
        if (r[8] == null) throw new ProjecaoIncoerenteException("LIQUIDACAO_SEM_ORIGEM");
        long id = ((Number) r[0]).longValue();
        String negocio = r[1].toString(), conta = r[3].toString(), ticker = r[4].toString();
        LadoNegocio lado = LadoNegocio.valueOf(r[2].toString());
        Montante montante = lado == LadoNegocio.COMPRADOR ? Montante.ativo(ticker, ((Number) r[5]).longValue())
                : Montante.brl((BigDecimal) r[6]);
        ContaContabil destino = lado == LadoNegocio.COMPRADOR ? CUSTODIA : CAIXA;
        ContaContabil origem = lado == LadoNegocio.COMPRADOR ? CUSTODIA_A_RECEBER : CAIXA_A_RECEBER;
        var transacao = new TransacaoContabil(negocio, lado, conta, Momento.D2,
                List.of(new Partida(destino, DEBITO, montante), new Partida(origem, CREDITO, montante)));
        int alteradas = em().createNativeQuery("UPDATE bo_liquidacao SET estado='LIQUIDADO' WHERE id=:i AND estado='PENDENTE'")
                .setParameter("i", id).executeUpdate();
        if (alteradas != 1) throw new ProjecaoIncoerenteException("LIQUIDACAO_CONCORRENTE");
        LocalDate vencimento = LocalDate.parse(r[7].toString());
        razao.liquidar(transacao, vencimento);
        String valor = lado == LadoNegocio.COMPRADOR ? Long.toString(((Number) r[5]).longValue())
                : ((BigDecimal) r[6]).toPlainString();
        String payload = EventoCredito.criar(negocio, lado.name(), conta, ticker, valor, vencimento,
                new EventoCredito.Origem(UUID.fromString(r[8].toString()), Instant.now()));
        em().createNativeQuery("""
                INSERT INTO bo_outbox(id,topico,chave,tipo,payload)
                VALUES(:i,'liquidacao.eventos.v1',:c,'LIQUIDACAO_EFETIVADA',:p)
                """).setParameter("i", EventoCredito.id(negocio, lado.name())).setParameter("c", conta)
                .setParameter("p", payload).executeUpdate();
    }

    private void exigirDisponivel() {
        if (!em().createNativeQuery("SELECT motivo FROM bo_liquidacao_controle WHERE id=1").getResultList().isEmpty())
            throw new MovimentoInvalidoException(503, "LIQUIDACAO_INDISPONIVEL");
    }

    /** Constraints e invariantes não são indisponibilidade transitória; wrappers JTA/Hibernate preservam a causa. */
    public static boolean invariante(Throwable erro) {
        for (Throwable causa = erro; causa != null; causa = causa.getCause()) {
            if (causa instanceof ProjecaoIncoerenteException) return true;
            if (causa instanceof java.sql.SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("23")) return true;
        }
        return false;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void quarentena() {
        em().createNativeQuery("INSERT INTO bo_liquidacao_controle(id,motivo) VALUES(1,'INVARIANTE_LIQUIDACAO') ON CONFLICT DO NOTHING")
                .executeUpdate();
    }
}
