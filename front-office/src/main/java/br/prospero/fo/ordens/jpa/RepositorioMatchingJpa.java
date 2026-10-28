package br.prospero.fo.ordens.jpa;

import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.dominio.Ordem;
import br.prospero.fo.ordens.dominio.MatchingIncoerenteException;
import br.prospero.fo.ordens.dominio.RepositorioMatching;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import br.prospero.fo.ordens.helper.ProjetarMatching;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Progresso dos dois lados e fatos de deduplicação confirmam na mesma transação. */
@ApplicationScoped
public class RepositorioMatchingJpa implements RepositorioMatching {
    private final Instance<EntityManager> gerenciadores;
    private final Timer boletaNegocio;
    @Inject
    RepositorioTrajetoriaJpa trajetoria;

    @Inject
    public RepositorioMatchingJpa(Instance<EntityManager> gerenciadores, MeterRegistry metricas) {
        this.gerenciadores = gerenciadores;
        // RNF04 (RF05d): do registro da ordem agressora até a projeção do seu primeiro negócio, no mesmo relógio.
        this.boletaNegocio = Timer.builder("prospero.ordens.boleta_negocio")
                .description("Recebimento da ordem agressora até a projeção do primeiro negócio")
                .publishPercentileHistogram()
                // Buckets finos em torno da meta de 50 ms: sem eles o p99 sairia como o limite do bucket padrão (67,1 ms).
                .serviceLevelObjectives(Duration.ofMillis(25), Duration.ofMillis(35), Duration.ofMillis(45),
                        Duration.ofMillis(50), Duration.ofMillis(60), Duration.ofMillis(75)).register(metricas);
    }

    private EntityManager em() {
        return gerenciadores.get();
    }

    @Override
    @Transactional
    public boolean aplicar(EventoMatching e) {
        // Guardião transacional também serializa repetição concorrente do mesmo ID.
        em().createNativeQuery("SELECT pg_advisory_xact_lock(:chave)")
                .setParameter("chave", e.id().getMostSignificantBits()).getSingleResult();
        if (repetida("matching_evento", e.id(), e.assinatura())) {
            return false;
        }
        if (e.detalhe() instanceof EventoMatching.SessaoEncerrada marco) {
            return aplicarMarco(e, marco);
        }
        exigirCorrelacaoECausacao(e);
        List<EventoMatching.Alvo> alvos = alvos(e.detalhe()).stream()
                .sorted(Comparator.comparing(a -> a.ordem().toString())).toList();
        List<OrdemEntity> entidades = travar(alvos);
        exigirVersao(e, entidades);
        if (e.detalhe() instanceof EventoMatching.Negocio n && negocioRepetido(e, n)) {
            registrar(e);
            return false;
        }
        Object[] p = parametrosDoAtivo(e.ticker());
        List<Ordem> atualizadas = new ArrayList<>();
        for (int i = 0; i < entidades.size(); i++) {
            Ordem antiga = RepositorioOrdensJpa.ordem(entidades.get(i));
            atualizadas.add(ProjetarMatching.aplicar(antiga, e, alvos.get(i), (BigDecimal) p[0], ((Number) p[1]).longValue()));
        }
        if (e.detalhe() instanceof EventoMatching.Negocio n) {
            medirPrimeiroNegocioDoAgressor(n, alvos, entidades);
        }
        persistir(entidades, atualizadas);
        if (e.detalhe() instanceof EventoMatching.Negocio n) {
            em().createNativeQuery("INSERT INTO matching_negocio (id, assinatura) VALUES (:id, :assinatura)")
                    .setParameter("id", n.id()).setParameter("assinatura", EnvelopeMatching.assinaturaNegocio(e, n)).executeUpdate();
        }
        registrar(e);
        trajetoria.registrarMatching(e, entidades);
        return true;
    }

    /** Marco v4: correlação e causação são o encerramento determinístico do ativo/data. */
    private boolean aplicarMarco(EventoMatching e, EventoMatching.SessaoEncerrada marco) {
        UUID esperado = EventoMatching.encerramento(e.ticker(), e.sessao());
        UUID id = UUID.nameUUIDFromBytes(("matching:" + esperado + ":" + marco.expiradas()).getBytes(StandardCharsets.UTF_8));
        if (!esperado.equals(e.correlacao()) || !esperado.equals(e.causacao()) || !id.equals(e.id())) {
            throw new MatchingIncoerenteException("MATCHING_ENCERRAMENTO");
        }
        registrar(e);
        return true;
    }

    /** Admissão correlacionada e causação existem no mesmo ativo/sessão; correlação do agressor e da própria ordem. */
    private void exigirCorrelacaoECausacao(EventoMatching e) {
        if (em().createNativeQuery("SELECT 1 FROM ordem WHERE admissao_matching = :admissao AND ticker = :ticker AND data_sessao = :sessao")
                .setParameter("admissao", e.correlacao()).setParameter("ticker", e.ticker())
                .setParameter("sessao", e.sessao()).getResultList().isEmpty()) {
            throw new MatchingIncoerenteException("MATCHING_CORRELACAO");
        }
        if (e.versao() == 4 && !EventoMatching.encerramento(e.ticker(), e.sessao()).equals(e.causacao())) {
            throw new MatchingIncoerenteException("MATCHING_CAUSACAO");
        }
        if (e.causacao() != null && e.versao() != 4 && e.versao() != 5 && em().createNativeQuery(
                "SELECT 1 FROM ordem WHERE admissao_matching=:a AND ticker=:t AND data_sessao=:s"
                        + (e.versao() == 3 ? "" : " AND grupo_oco IS NULL"))
                .setParameter("a", e.causacao()).setParameter("t", e.ticker()).setParameter("s", e.sessao())
                .getResultList().isEmpty()) {
            throw new MatchingIncoerenteException("MATCHING_CAUSACAO");
        }
        if (e.detalhe() instanceof EventoMatching.Negocio n) {
            var agressora = n.agressor() == br.prospero.fo.ordens.dominio.LadoOrdem.COMPRA ? n.compra() : n.venda();
            if (!admissao(agressora).equals(e.correlacao())) {
                throw new MatchingIncoerenteException("MATCHING_CORRELACAO_AGRESSOR");
            }
        }
        EventoMatching.Alvo propria = propria(e);
        if (propria != null && !e.correlacao().equals(admissao(propria))) {
            throw new MatchingIncoerenteException("MATCHING_CORRELACAO_ORDEM");
        }
    }

    /** Ordem cuja admissão o fato correlaciona; Oferta/Encerrada v1 e Negócio não têm alvo próprio. */
    private static EventoMatching.Alvo propria(EventoMatching e) {
        return switch (e.detalhe()) {
            case EventoMatching.Aceita a -> a.alvo();
            case EventoMatching.Rejeitada r -> r.alvo();
            case EventoMatching.StopPendente p -> p.alvo();
            case EventoMatching.StopAcionado a -> a.alvo();
            case EventoMatching.Oferta o -> e.causacao() != null ? o.alvo() : null;
            case EventoMatching.Encerrada f -> e.causacao() != null ? f.alvo() : null;
            case EventoMatching.Reduzida r -> r.alvo();
            case EventoMatching.SessaoEncerrada m -> null;
            case EventoMatching.CancelamentoDecidido d -> d.alvo();
            default -> null;
        };
    }

    private static UUID admissao(EventoMatching.Alvo alvo) {
        return UUID.nameUUIDFromBytes(("admissao:" + alvo.intencao()).getBytes(StandardCharsets.UTF_8));
    }

    /** Trava as ordens em ordem estável de ID. */
    private List<OrdemEntity> travar(List<EventoMatching.Alvo> alvos) {
        List<OrdemEntity> entidades = new ArrayList<>();
        for (var alvo : alvos) {
            OrdemEntity entidade = em().find(OrdemEntity.class, alvo.ordem(), LockModeType.PESSIMISTIC_WRITE);
            if (entidade == null) {
                throw new MatchingIncoerenteException("MATCHING_ORDEM_AUSENTE");
            }
            entidades.add(entidade);
        }
        return entidades;
    }

    /** v3 exatamente quando há perna OCO; senão v2 exatamente quando há stop (ADR-013). */
    private void exigirVersao(EventoMatching e, List<OrdemEntity> entidades) {
        List<?> causas = em().createNativeQuery(
                "SELECT tipo, grupo_oco FROM ordem WHERE admissao_matching=:a AND ticker=:t AND data_sessao=:s")
                .setParameter("a", e.causacao() == null ? e.correlacao() : e.causacao())
                .setParameter("t", e.ticker()).setParameter("s", e.sessao()).getResultList();
        boolean envolveOco = causas.stream().anyMatch(c -> ((Object[]) c)[1] != null)
                || entidades.stream().anyMatch(o -> o.grupoOco != null);
        boolean envolveStop = causas.stream().map(c -> ((Object[]) c)[0]).anyMatch(t -> t.equals("STOP") || t.equals("STOP_LIMIT"))
                || entidades.stream().anyMatch(o -> o.tipo.equals("STOP") || o.tipo.equals("STOP_LIMIT"));
        if (e.versao() != 4 && e.versao() != 5 && e.versao() != (envolveOco ? 3 : envolveStop ? 2 : 1)) {
            throw new MatchingIncoerenteException("MATCHING_VERSAO_STOP");
        }
    }

    /** Negócio já projetado por outro fato (o mesmo negócio chega nos dois lados). */
    private boolean negocioRepetido(EventoMatching e, EventoMatching.Negocio n) {
        String assinatura = EnvelopeMatching.assinaturaNegocio(e, n);
        long chave = UUID.nameUUIDFromBytes(n.id().getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
        em().createNativeQuery("SELECT pg_advisory_xact_lock(:chave)").setParameter("chave", chave).getSingleResult();
        return repetida("matching_negocio", n.id(), assinatura);
    }

    private Object[] parametrosDoAtivo(String ticker) {
        List<?> parametros = em().createNativeQuery("SELECT tick, lote FROM ativo WHERE ticker = :ticker")
                .setParameter("ticker", ticker).getResultList();
        if (parametros.size() != 1) {
            throw new MatchingIncoerenteException("MATCHING_ATIVO_AUSENTE");
        }
        return (Object[]) parametros.getFirst();
    }

    private static void persistir(List<OrdemEntity> entidades, List<Ordem> atualizadas) {
        for (int i = 0; i < entidades.size(); i++) {
            OrdemEntity entidade = entidades.get(i);
            Ordem nova = atualizadas.get(i);
            entidade.estado = nova.estado().name();
            entidade.quantidadeExecutada = nova.quantidadeExecutada();
            entidade.quantidadeCancelada = nova.quantidadeCancelada();
            entidade.quantidadeReduzida = nova.quantidadeReduzida();
            entidade.motivoEncerramento = nova.motivoEncerramento();
            entidade.motivoRejeicao = nova.motivoRejeicao() == null ? null : nova.motivoRejeicao().name();
        }
    }

    private void medirPrimeiroNegocioDoAgressor(EventoMatching.Negocio n, List<EventoMatching.Alvo> alvos,
                                                List<OrdemEntity> entidades) {
        var agressora = n.agressor() == br.prospero.fo.ordens.dominio.LadoOrdem.COMPRA ? n.compra() : n.venda();
        for (int i = 0; i < alvos.size(); i++) {
            OrdemEntity o = entidades.get(i);
            if (alvos.get(i).equals(agressora) && o.quantidadeExecutada == 0 && o.recebidaEm != null) {
                boletaNegocio.record(Duration.between(o.recebidaEm, Instant.now()));
            }
        }
    }

    private boolean repetida(String tabela, Object id, String assinatura) {
        // Tabela vem apenas dos dois literais internos, nunca de entrada externa.
        List<?> existente = em().createNativeQuery("SELECT assinatura FROM " + tabela + " WHERE id = :id")
                .setParameter("id", id).getResultList();
        if (existente.isEmpty()) {
            return false;
        }
        if (!assinatura.equals(existente.getFirst().toString().trim())) {
            throw new MatchingIncoerenteException("MATCHING_IDENTIDADE_CONFLITANTE");
        }
        return true;
    }

    private void registrar(EventoMatching e) {
        em().createNativeQuery("INSERT INTO matching_evento (id, assinatura) VALUES (:id, :assinatura)")
                .setParameter("id", e.id()).setParameter("assinatura", e.assinatura()).executeUpdate();
    }

    private static List<EventoMatching.Alvo> alvos(EventoMatching.Detalhe detalhe) {
        return switch (detalhe) {
            case EventoMatching.Negocio n -> List.of(n.compra(), n.venda());
            case EventoMatching.Aceita a -> List.of(a.alvo());
            case EventoMatching.Oferta o -> List.of(o.alvo());
            case EventoMatching.StopPendente p -> List.of(p.alvo());
            case EventoMatching.StopAcionado a -> List.of(a.alvo());
            case EventoMatching.Rejeitada r -> List.of(r.alvo());
            case EventoMatching.Encerrada f -> List.of(f.alvo());
            case EventoMatching.Reduzida r -> List.of(r.alvo());
            case EventoMatching.SessaoEncerrada m -> List.of();
            case EventoMatching.CancelamentoDecidido d -> List.of(d.alvo());
        };
    }
}
