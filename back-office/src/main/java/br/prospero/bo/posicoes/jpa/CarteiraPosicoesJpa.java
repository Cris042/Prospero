package br.prospero.bo.posicoes.jpa;

import br.prospero.bo.posicoes.dominio.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@ApplicationScoped
public class CarteiraPosicoesJpa implements ConsultaPosicoes {
    private final Instance<EntityManager> gerenciadores;
    @Inject public CarteiraPosicoesJpa(Instance<EntityManager> gerenciadores) { this.gerenciadores = gerenciadores; }
    private EntityManager em() { return gerenciadores.get(); }

    public Posicao buscar(String conta, String ticker) {
        List<?> linhas = em().createNativeQuery("""
                SELECT quantidade,medio_n,medio_d,realizado_n,realizado_d FROM bo_posicao
                WHERE conta=:c AND ticker=:t FOR UPDATE
                """).setParameter("c", conta).setParameter("t", ticker).getResultList();
        if (linhas.size() != 1) throw new ProjecaoIncoerenteException("POSICAO_AUSENTE");
        return posicao((Object[]) linhas.getFirst(), 0);
    }

    public void salvar(String conta, String ticker, Posicao p) {
        em().createNativeQuery("""
                UPDATE bo_posicao SET quantidade=:q,medio_n=:mn,medio_d=:md,realizado_n=:rn,realizado_d=:rd
                WHERE conta=:c AND ticker=:t
                """).setParameter("q", p.quantidade()).setParameter("mn", p.medio() == null ? null : p.medio().numerador().toString())
                .setParameter("md", p.medio() == null ? null : p.medio().denominador().toString())
                .setParameter("rn", p.realizado().numerador().toString()).setParameter("rd", p.realizado().denominador().toString())
                .setParameter("c", conta).setParameter("t", ticker).executeUpdate();
    }

    public void validarSessao(EventoMatching e) {
        Object[] linha = (Object[]) em().createNativeQuery("SELECT sessao,encerrada FROM bo_cotacao WHERE ticker=:t FOR UPDATE")
                .setParameter("t", e.ticker()).getSingleResult();
        if (linha[0] != null && !e.sessao().equals(LocalDate.parse(linha[0].toString()))) {
            throw new ProjecaoIncoerenteException("SESSAO_DIVERGENTE");
        }
        if (Boolean.TRUE.equals(linha[1]) && e.detalhe() instanceof EventoMatching.Negocio) {
            throw new ProjecaoIncoerenteException("NEGOCIO_APOS_ENCERRAMENTO");
        }
        em().createNativeQuery("UPDATE bo_cotacao SET sessao=coalesce(sessao,:s) WHERE ticker=:t")
                .setParameter("s", e.sessao()).setParameter("t", e.ticker()).executeUpdate();
    }

    public void cotar(EventoMatching e, EventoMatching.Negocio n) {
        em().createNativeQuery("""
                UPDATE bo_cotacao SET preco=:p,revisao=revisao+1,atualizada_em=now() WHERE ticker=:t
                """).setParameter("p", n.preco()).setParameter("t", e.ticker()).executeUpdate();
    }

    public void encerrar(String ticker) {
        em().createNativeQuery("UPDATE bo_cotacao SET encerrada=true,revisao=revisao+1,atualizada_em=now() WHERE ticker=:t")
                .setParameter("t", ticker).executeUpdate();
    }

    @Override @Transactional
    public List<EstadoPosicao> consultar(String conta) {
        List<?> linhas = em().createNativeQuery("""
                SELECT p.ticker,p.quantidade,p.medio_n,p.medio_d,p.realizado_n,p.realizado_d,
                       c.preco,c.sessao,c.encerrada,c.revisao,c.atualizada_em
                FROM bo_posicao p JOIN bo_cotacao c ON c.ticker=p.ticker WHERE p.conta=:c ORDER BY p.ticker
                """).setParameter("c", conta).setMaxResults(5).getResultList();
        return linhas.stream().map(o -> estado((Object[]) o)).toList();
    }

    private static EstadoPosicao estado(Object[] r) {
        return new EstadoPosicao(r[0].toString(), posicao(r, 1), (BigDecimal) r[6],
                r[7] == null ? null : LocalDate.parse(r[7].toString()), (Boolean) r[8], ((Number) r[9]).longValue(), instante(r[10]));
    }

    private static Posicao posicao(Object[] r, int inicio) {
        return new Posicao(((Number) r[inicio]).longValue(), r[inicio + 1] == null ? null : racional(r[inicio + 1], r[inicio + 2]),
                racional(r[inicio + 3], r[inicio + 4]));
    }

    private static Racional racional(Object n, Object d) {
        return Racional.fracao(new BigInteger(n.toString()), new BigInteger(d.toString()));
    }

    private static Instant instante(Object valor) {
        if (valor == null) return null;
        return switch (valor) {
            case Instant i -> i;
            case java.time.OffsetDateTime d -> d.toInstant();
            case java.sql.Timestamp t -> t.toInstant();
            default -> throw new ProjecaoIncoerenteException("INSTANTE_INVALIDO");
        };
    }
}
