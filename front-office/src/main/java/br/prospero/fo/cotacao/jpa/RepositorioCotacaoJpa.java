package br.prospero.fo.cotacao.jpa;

import br.prospero.fo.cotacao.dominio.*;
import br.prospero.fo.cotacao.helper.ProjetarCotacao;
import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.dominio.LadoOrdem;
import br.prospero.fo.ordens.dominio.MatchingIncoerenteException;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Deduplicação e projeção confirmadas juntas; leitura do livro usa um único snapshot PostgreSQL. */
@ApplicationScoped
public class RepositorioCotacaoJpa implements RepositorioCotacao {
    private final Instance<EntityManager> gerenciadores;

    @Inject
    public RepositorioCotacaoJpa(Instance<EntityManager> gerenciadores) {
        this.gerenciadores = gerenciadores;
    }

    private EntityManager em() { return gerenciadores.get(); }

    @Override
    @Transactional
    public boolean aplicar(EventoMatching evento) {
        travar("cotacao-evento:" + evento.id());
        if (repetido("cotacao_evento", evento.id(), evento.assinatura())) return false;
        travar("cotacao-ativo:" + evento.ticker());
        validarAtivo(evento);
        validarCorrelacao(evento);
        if (evento.detalhe() instanceof EventoMatching.Negocio negocio
                && repetido("cotacao_negocio", negocio.id(), EnvelopeMatching.assinaturaNegocio(evento, negocio))) {
            registrar(evento);
            return false;
        }
        var mutacoes = ProjetarCotacao.mutacoes(evento.detalhe());
        boolean mudou = false;
        Optional<SessaoCotacao> anterior = sessao(evento.ticker(), evento.sessao());
        for (var mutacao : mutacoes) {
            if (anterior.isPresent() && anterior.get().encerrada()
                    && !(mutacao instanceof MutacaoCotacao.Retirar) && !(mutacao instanceof MutacaoCotacao.Encerrar)) {
                throw incoerente("SESSAO_ENCERRADA");
            }
            mudou |= aplicarMutacao(evento, mutacao, anterior);
        }
        if (mudou) {
            em().createNativeQuery("""
                    INSERT INTO cotacao_sessao(ticker,sessao,revisao,atualizado_em)
                    VALUES(:t,:s,1,now()) ON CONFLICT(ticker,sessao)
                    DO UPDATE SET revisao=cotacao_sessao.revisao+1, atualizado_em=now()
                    """).setParameter("t", evento.ticker()).setParameter("s", evento.sessao()).executeUpdate();
            if (evento.detalhe() instanceof EventoMatching.SessaoEncerrada) {
                em().createNativeQuery("UPDATE cotacao_sessao SET encerrada=true WHERE ticker=:t AND sessao=:s")
                        .setParameter("t", evento.ticker()).setParameter("s", evento.sessao()).executeUpdate();
            }
        }
        if (evento.detalhe() instanceof EventoMatching.Negocio negocio) {
            em().createNativeQuery("INSERT INTO cotacao_negocio(id,assinatura) VALUES(:id,:a)")
                    .setParameter("id", negocio.id()).setParameter("a", EnvelopeMatching.assinaturaNegocio(evento, negocio)).executeUpdate();
        }
        registrar(evento);
        return mudou;
    }

    private boolean aplicarMutacao(EventoMatching e, MutacaoCotacao m, Optional<SessaoCotacao> anterior) {
        return switch (m) {
            case MutacaoCotacao.Repousar r -> {
                var existente = oferta(r.ordem());
                if (existente.isPresent()) {
                    Object[] o = existente.get();
                    exigirIdentidade(o, e);
                    if (!o[2].equals(r.lado()) || ((BigDecimal) o[3]).compareTo(r.preco()) != 0
                            || ((Number) o[4]).longValue() != r.restante()) throw incoerente("OFERTA_CONFLITANTE");
                    yield false;
                }
                em().createNativeQuery("INSERT INTO cotacao_oferta(ordem,ticker,sessao,lado,preco,restante) VALUES(:o,:t,:s,:l,:p,:q)")
                        .setParameter("o", r.ordem()).setParameter("t", e.ticker()).setParameter("s", e.sessao())
                        .setParameter("l", r.lado()).setParameter("p", r.preco()).setParameter("q", r.restante()).executeUpdate();
                yield true;
            }
            case MutacaoCotacao.Consumir c -> {
                Object[] o = oferta(c.ordem()).orElseThrow(() -> incoerente("PASSIVA_AUSENTE"));
                exigirIdentidade(o, e);
                var negocio = (EventoMatching.Negocio) e.detalhe();
                String lado = negocio.agressor() == LadoOrdem.COMPRA ? "VENDA" : "COMPRA";
                long saldo = ((Number) o[4]).longValue();
                if (!lado.equals(o[2]) || ((BigDecimal) o[3]).compareTo(negocio.preco()) != 0
                        || c.quantidade() <= 0 || c.quantidade() > saldo) throw incoerente("PASSIVA_INCOERENTE");
                ajustarOuRetirar(c.ordem(), saldo - c.quantidade());
                yield true;
            }
            case MutacaoCotacao.Retirar r -> {
                var o = oferta(r.ordem());
                if (o.isEmpty()) yield false; // IOC, FOK e stops podem encerrar sem jamais repousar.
                exigirIdentidade(o.get(), e);
                var terminal = (EventoMatching.Encerrada) e.detalhe();
                long saldo = ((Number) o.get()[4]).longValue();
                if ("EXECUTADA".equals(terminal.motivo()) || terminal.cancelada() != saldo) {
                    throw incoerente("TERMINAL_INCOERENTE");
                }
                ajustarOuRetirar(r.ordem(), 0);
                yield true;
            }
            case MutacaoCotacao.Ajustar a -> {
                var o = oferta(a.ordem());
                if (o.isEmpty()) yield false; // Redução de stop pendente OCO não muda o livro.
                exigirIdentidade(o.get(), e);
                long saldo = ((Number) o.get()[4]).longValue();
                var reducao = (EventoMatching.Reduzida) e.detalhe();
                if (a.restante() <= 0 || a.restante() >= saldo || saldo - a.restante() != reducao.reduzida()) {
                    throw incoerente("REDUCAO_INCOERENTE");
                }
                ajustarOuRetirar(a.ordem(), a.restante());
                yield true;
            }
            case MutacaoCotacao.Negociar n -> {
                SessaoCotacao s = anterior.orElseThrow(() -> incoerente("SESSAO_AUSENTE"));
                long quantidade;
                try { quantidade = Math.addExact(s.volumeQuantidade(), n.quantidade()); }
                catch (ArithmeticException erro) { throw incoerente("VOLUME_EXCEDIDO"); }
                em().createNativeQuery("""
                        UPDATE cotacao_sessao SET ultimo_preco=:p,volume_quantidade=:q,
                        volume_financeiro=:v WHERE ticker=:t AND sessao=:s
                        """).setParameter("p", n.preco()).setParameter("q", quantidade)
                        .setParameter("v", s.volumeFinanceiro().add(n.preco().multiply(BigDecimal.valueOf(n.quantidade()))))
                        .setParameter("t", e.ticker()).setParameter("s", e.sessao()).executeUpdate();
                yield true;
            }
            case MutacaoCotacao.Encerrar ignorado -> {
                em().createNativeQuery("DELETE FROM cotacao_oferta WHERE ticker=:t AND sessao=:s")
                        .setParameter("t", e.ticker()).setParameter("s", e.sessao()).executeUpdate();
                yield anterior.isEmpty() || !anterior.get().encerrada();
            }
        };
    }

    private void validarAtivo(EventoMatching e) {
        List<?> ativos = em().createNativeQuery("SELECT tick,lote FROM ativo WHERE ticker=:t")
                .setParameter("t", e.ticker()).getResultList();
        if (ativos.size() != 1) throw incoerente("ATIVO_AUSENTE");
        Object[] a = (Object[]) ativos.getFirst();
        for (var m : ProjetarCotacao.mutacoes(e.detalhe())) {
            BigDecimal preco = switch (m) {
                case MutacaoCotacao.Repousar r -> r.preco();
                case MutacaoCotacao.Negociar n -> n.preco();
                default -> null;
            };
            long quantidade = switch (m) {
                case MutacaoCotacao.Repousar r -> r.restante();
                case MutacaoCotacao.Negociar n -> n.quantidade();
                case MutacaoCotacao.Ajustar r -> r.restante();
                default -> 0;
            };
            if (preco != null && (preco.signum() <= 0 || preco.remainder((BigDecimal) a[0]).signum() != 0)
                    || quantidade < 0 || quantidade % ((Number) a[1]).longValue() != 0) throw incoerente("TICK_LOTE");
        }
    }

    private void validarCorrelacao(EventoMatching e) {
        if (e.detalhe() instanceof EventoMatching.SessaoEncerrada marco) {
            validarMarco(e, marco);
            return;
        }
        EventoMatching.Alvo alvo = alvo(e);
        // Encerrada v1 pode ser a passiva cujo término é causado pela admissão agressora.
        if (alvo != null && !e.correlacao().equals(UUID.nameUUIDFromBytes(
                ("admissao:" + alvo.intencao()).getBytes(StandardCharsets.UTF_8)))) throw incoerente("CORRELACAO");
        if (e.versao() == 4 && !EventoMatching.encerramento(e.ticker(), e.sessao()).equals(e.causacao())) {
            throw incoerente("CAUSACAO");
        }
    }

    private static void validarMarco(EventoMatching e, EventoMatching.SessaoEncerrada marco) {
        UUID causa = EventoMatching.encerramento(e.ticker(), e.sessao());
        UUID id = UUID.nameUUIDFromBytes(("matching:" + causa + ":" + marco.expiradas()).getBytes(StandardCharsets.UTF_8));
        if (!causa.equals(e.causacao()) || !causa.equals(e.correlacao()) || !id.equals(e.id())) throw incoerente("MARCO");
    }

    private static EventoMatching.Alvo alvo(EventoMatching e) {
        return switch (e.detalhe()) {
            case EventoMatching.Negocio n -> n.agressor() == LadoOrdem.COMPRA ? n.compra() : n.venda();
            case EventoMatching.Oferta o -> o.alvo();
            case EventoMatching.Aceita a -> a.alvo();
            case EventoMatching.Rejeitada r -> r.alvo();
            case EventoMatching.StopPendente s -> s.alvo();
            case EventoMatching.StopAcionado s -> s.alvo();
            case EventoMatching.Reduzida r -> r.alvo();
            case EventoMatching.Encerrada f -> e.causacao() != null ? f.alvo() : null;
            case EventoMatching.SessaoEncerrada ignorado -> null;
            case EventoMatching.CancelamentoDecidido d -> d.alvo();
        };
    }

    private Optional<Object[]> oferta(UUID ordem) {
        List<?> linhas = em().createNativeQuery("SELECT ticker,sessao,lado,preco,restante FROM cotacao_oferta WHERE ordem=:o")
                .setParameter("o", ordem).getResultList();
        return linhas.isEmpty() ? Optional.empty() : Optional.of((Object[]) linhas.getFirst());
    }

    private static void exigirIdentidade(Object[] o, EventoMatching e) {
        if (!o[0].equals(e.ticker()) || !LocalDate.parse(o[1].toString()).equals(e.sessao())) throw incoerente("OFERTA_IDENTIDADE");
    }

    private void ajustarOuRetirar(UUID ordem, long restante) {
        if (restante == 0) {
            em().createNativeQuery("DELETE FROM cotacao_oferta WHERE ordem=:o").setParameter("o", ordem).executeUpdate();
            return;
        }
        em().createNativeQuery("UPDATE cotacao_oferta SET restante=:q WHERE ordem=:o")
                .setParameter("q", restante).setParameter("o", ordem).executeUpdate();
    }

    private void travar(String chave) {
        em().createNativeQuery("SELECT pg_advisory_xact_lock(:c)").setParameter("c",
                UUID.nameUUIDFromBytes(chave.getBytes(StandardCharsets.UTF_8)).getMostSignificantBits()).getSingleResult();
    }

    private boolean repetido(String tabela, Object id, String assinatura) {
        // Tabela é um dos dois literais internos; valores externos são sempre parâmetros.
        List<?> linhas = em().createNativeQuery("SELECT assinatura FROM " + tabela + " WHERE id=:id")
                .setParameter("id", id).getResultList();
        if (linhas.isEmpty()) return false;
        if (!assinatura.equals(linhas.getFirst().toString().trim())) throw incoerente("IDENTIDADE_CONFLITANTE");
        return true;
    }

    private void registrar(EventoMatching e) {
        em().createNativeQuery("INSERT INTO cotacao_evento(id,assinatura) VALUES(:id,:a)")
                .setParameter("id", e.id()).setParameter("a", e.assinatura()).executeUpdate();
    }

    @Override
    @Transactional
    public Optional<SessaoCotacao> sessao(String ticker, LocalDate sessao) {
        List<?> linhas = em().createNativeQuery("""
                SELECT ticker,sessao,ultimo_preco,volume_quantidade,volume_financeiro,revisao,
                atualizado_em,encerrada FROM cotacao_sessao WHERE ticker=:t AND sessao=:s
                """).setParameter("t", ticker).setParameter("s", sessao).getResultList();
        return linhas.isEmpty() ? Optional.empty() : Optional.of(sessao((Object[]) linhas.getFirst()));
    }

    @Override
    @Transactional
    public Optional<LivroCotado> livro(String ticker) {
        List<?> linhas = em().createNativeQuery("""
                WITH s AS (SELECT * FROM cotacao_sessao WHERE ticker=:t ORDER BY sessao DESC LIMIT 1),
                niveis AS (SELECT o.lado,o.preco,sum(o.restante) AS quantidade,count(*) AS ofertas
                    FROM cotacao_oferta o JOIN s ON o.ticker=s.ticker AND o.sessao=s.sessao GROUP BY o.lado,o.preco),
                classificados AS (SELECT *,row_number() OVER w AS posicao,sum(quantidade) OVER w AS acumulada,
                    count(*) OVER (PARTITION BY lado) AS niveis_total,
                    sum(quantidade) OVER (PARTITION BY lado) AS quantidade_total
                    FROM niveis WINDOW w AS (PARTITION BY lado ORDER BY
                        CASE WHEN lado='COMPRA' THEN -preco ELSE preco END))
                SELECT s.ticker,s.sessao,s.ultimo_preco,s.volume_quantidade,s.volume_financeiro,s.revisao,
                    s.atualizado_em,s.encerrada,n.lado,n.preco,n.quantidade,n.ofertas,n.acumulada,n.niveis_total,n.quantidade_total
                FROM s LEFT JOIN classificados n ON n.posicao<=5 ORDER BY n.lado,n.posicao
                """).setParameter("t", ticker).getResultList();
        if (linhas.isEmpty()) return Optional.empty();
        return Optional.of(new LivroCotado(sessao((Object[]) linhas.getFirst()), lado(linhas, "COMPRA"), lado(linhas, "VENDA")));
    }

    private static LadoCotado lado(List<?> linhas, String lado) {
        List<NivelCotado> niveis = new ArrayList<>();
        int total = 0;
        long quantidade = 0;
        for (Object linha : linhas) {
            Object[] r = (Object[]) linha;
            if (!lado.equals(r[8])) continue;
            niveis.add(new NivelCotado((BigDecimal) r[9], numero(r[10]), Math.toIntExact(numero(r[11])), numero(r[12])));
            total = Math.toIntExact(numero(r[13]));
            quantidade = numero(r[14]);
        }
        return new LadoCotado(niveis, total, quantidade);
    }

    private static long numero(Object n) {
        return n instanceof BigDecimal d ? d.longValueExact() : ((Number) n).longValue();
    }

    private static SessaoCotacao sessao(Object[] r) {
        Instant instante = switch (r[6]) {
            case Instant i -> i;
            case java.time.OffsetDateTime d -> d.toInstant();
            case java.sql.Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("Tipo de instante não suportado");
        };
        return new SessaoCotacao(r[0].toString(), LocalDate.parse(r[1].toString()), (BigDecimal) r[2], numero(r[3]),
                (BigDecimal) r[4], numero(r[5]), instante, (Boolean) r[7]);
    }

    private static MatchingIncoerenteException incoerente(String motivo) {
        return new MatchingIncoerenteException("COTACAO_" + motivo);
    }
}
