package br.prospero.fo.ordens.jpa;

import br.prospero.fo.comum.dominio.ParticaoBloqueadaException;

import br.prospero.fo.comum.dominio.MensagemSaida;
import br.prospero.fo.comum.kafka.AvisoOutbox;
import br.prospero.fo.comum.kafka.Rastreio;
import br.prospero.fo.ordens.dominio.EstadoCancelamento;
import br.prospero.fo.ordens.dominio.EstadoOrdem;
import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.dominio.MarcoCancelamento;
import br.prospero.fo.ordens.dominio.Ordem;
import br.prospero.fo.ordens.dominio.OperacaoCancelamento;
import br.prospero.fo.ordens.dominio.RepositorioCancelamento;
import br.prospero.fo.ordens.dominio.ResultadoCancelamento;
import br.prospero.fo.ordens.dominio.ResultadoRisco;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import br.prospero.fo.ordens.helper.EnvelopeRisco;
import io.opentelemetry.api.trace.Span;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.hibernate.query.NativeQuery;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Operações de cancelamento no PostgreSQL. As transições só avançam por ranking: os resultados do ME (decisão) e do MO
 * (resultado) vêm de tópicos diferentes e podem chegar fora de ordem. A linha da operação é travada antes de mudar.
 */
@ApplicationScoped
public class RepositorioCancelamentoJpa implements RepositorioCancelamento {
    private static final String COLUNAS = "ordem, conta, estado, decisao, motivo, solicitada_em, atualizada_em";
    private final Instance<EntityManager> gerenciadores;
    @Inject RepositorioTrajetoriaJpa trajetoria;
    @Inject AvisoOutbox avisoOutbox;
    @Inject java.time.Clock relogio;

    @Inject
    public RepositorioCancelamentoJpa(Instance<EntityManager> gerenciadores) {
        this.gerenciadores = gerenciadores;
    }

    private EntityManager em() {
        return gerenciadores.get();
    }

    @Override
    @Transactional
    public Optional<Ordem> travarOrdem(String conta, UUID id) {
        return em().createQuery("from OrdemEntity where id = :id and conta = :c", OrdemEntity.class)
                .setParameter("id", id).setParameter("c", conta).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream().findFirst().map(RepositorioOrdensJpa::ordem);
    }

    @Override
    @Transactional
    public Optional<OperacaoCancelamento> buscar(String conta, UUID ordem) {
        return Optional.ofNullable(buscar(conta, List.of(ordem)).get(ordem));
    }

    @Override
    @Transactional
    public Map<UUID, OperacaoCancelamento> buscar(String conta, Collection<UUID> ordens) {
        Map<UUID, OperacaoCancelamento> resultado = new HashMap<>();
        if (ordens.isEmpty()) {
            return resultado;
        }
        em().createNativeQuery("SELECT " + COLUNAS + " FROM cancelamento_operacao WHERE conta = :c AND ordem IN (:o)")
                .setParameter("c", conta).setParameter("o", ordens).getResultList()
                .forEach(linha -> {
                    OperacaoCancelamento op = operacao((Object[]) linha);
                    resultado.put(op.ordem(), op);
                });
        return resultado;
    }

    @Override
    @Transactional
    public OperacaoCancelamento registrar(Ordem ordem, EstadoCancelamento estado, MensagemSaida comando, Instant agora) {
        em().createNativeQuery("""
                        INSERT INTO cancelamento_operacao (ordem, conta, estado, solicitada_em, atualizada_em)
                        VALUES (:o, :c, :e, :t, :t)""")
                .setParameter("o", ordem.id()).setParameter("c", ordem.conta()).setParameter("e", estado.name())
                .setParameter("t", OffsetDateTime.ofInstant(agora, ZoneOffset.UTC)).executeUpdate();
        if (comando != null) {
            emitir(ordem, comando, agora);
        }
        return new OperacaoCancelamento(ordem.id(), ordem.conta(), estado, null, null, agora, agora);
    }

    /**
     * Hook do risco, parte 1: trava as operações que aguardavam a decisão, antes do marco do risco, para manter a ordem
     * de lock ordem → operação → revisão da conta (a mesma do resultado do MO).
     */
    @Transactional
    public List<UUID> travarAguardandoRisco(ResultadoRisco risco, int alteradas) {
        if (alteradas == 0) {
            return List.of();
        }
        return em().createNativeQuery("""
                        SELECT c.ordem FROM cancelamento_operacao c JOIN ordem o ON o.id = c.ordem
                        WHERE o.conta = :c AND o.grupo_envio = :g AND c.estado = 'AGUARDANDO_RISCO'
                        ORDER BY c.ordem FOR UPDATE OF c""")
                .setParameter("c", risco.conta()).setParameter("g", risco.grupoEnvio()).getResultList().stream()
                .map(id -> (UUID) id).toList();
    }

    /** Hook do risco, parte 2: promove (RESERVADA) ou recusa (REJEITADA) os cancelamentos travados. */
    @Transactional
    public void aposRisco(ResultadoRisco risco, List<UUID> operacoes) {
        Instant agora = relogio.instant();
        for (UUID id : operacoes) {
            Ordem ordem = RepositorioOrdensJpa.ordem(em().find(OrdemEntity.class, id));
            if (risco.estado() == EstadoOrdem.RESERVADA) {
                promover(ordem, agora);
            } else {
                recusarPorRisco(ordem, agora);
            }
        }
    }

    private void promover(Ordem ordem, Instant agora) {
        String traceparent = Rastreio.traceparent(Span.current().getSpanContext());
        MensagemSaida comando = new MensagemSaida(EnvelopeRisco.idCancelamentoSolicitado(ordem.id()),
                EnvelopeRisco.TOPICO_COMANDOS, ordem.conta(), EnvelopeRisco.TIPO_CANCELAMENTO_SOLICITADO,
                EnvelopeRisco.cancelamentoSolicitado(ordem, agora, traceparent), traceparent, agora);
        atualizar(ordem.id(), EstadoCancelamento.SOLICITADO, null, agora);
        emitir(ordem, comando, agora);
    }

    private void recusarPorRisco(Ordem ordem, Instant agora) {
        atualizar(ordem.id(), EstadoCancelamento.RECUSADO, "RISCO_REJEITADO", agora);
        UUID evento = UUID.nameUUIDFromBytes(("cancelamento-recusa-risco:" + ordem.id()).getBytes(StandardCharsets.UTF_8));
        trajetoria.registrarCancelamento(ordem.conta(), ordem.id(), new MarcoCancelamento("front-office",
                "CANCELAMENTO_RECUSADO", evento,
                EnvelopeMatching.assinatura(("RISCO_REJEITADO:" + ordem.id()).getBytes(StandardCharsets.UTF_8)), agora,
                "RISCO_REJEITADO", null));
    }

    private void emitir(Ordem ordem, MensagemSaida comando, Instant agora) {
        em().createNativeQuery("""
                        INSERT INTO outbox (id, topico, chave, tipo, payload, traceparent, criado_em)
                        VALUES (:id, :topico, :chave, :tipo, :payload, :trace, :criado)""")
                .unwrap(NativeQuery.class)
                .setParameter("id", comando.id()).setParameter("topico", comando.topico())
                .setParameter("chave", comando.chave()).setParameter("tipo", comando.tipo())
                .setParameter("payload", comando.payload())
                .setParameter("trace", comando.traceparent(), String.class)
                .setParameter("criado", OffsetDateTime.ofInstant(comando.criadaEm(), ZoneOffset.UTC))
                .executeUpdate();
        trajetoria.registrarCancelamento(ordem.conta(), ordem.id(), new MarcoCancelamento("front-office",
                "CANCELAMENTO_SOLICITADO", comando.id(),
                EnvelopeMatching.assinatura(comando.payload().getBytes(StandardCharsets.UTF_8)), agora, null,
                comando.traceparent()));
        avisoOutbox.registrar();
    }

    @Override
    @Transactional
    public boolean aplicarResultado(ResultadoCancelamento r) {
        Optional<OperacaoCancelamento> atual = travar(r.ordem(), r.conta());
        if (atual.isEmpty()) {
            return false;
        }
        var decisao = atual.get().decisao();
        if (decisao != null && r.confirmado() != (decisao == EventoMatching.ResultadoCancelamento.APLICADO)) {
            throw new ParticaoBloqueadaException("RESULTADO_CANCELAMENTO_INVALIDO");
        }
        EstadoCancelamento novo = r.confirmado() ? EstadoCancelamento.CONFIRMADO : EstadoCancelamento.RECUSADO;
        if (!atual.get().estado().avancaPara(novo)) {
            return false;
        }
        atualizar(r.ordem(), novo, r.motivo(), r.ocorridoEm() == null ? relogio.instant() : r.ocorridoEm());
        trajetoria.registrarCancelamento(r.conta(), r.ordem(), new MarcoCancelamento("middle-office",
                r.confirmado() ? "CANCELAMENTO_CONFIRMADO" : "CANCELAMENTO_RECUSADO", r.evento(), r.assinatura(),
                r.ocorridoEm(), r.motivo(), r.traceparent()));
        return true;
    }

    /**
     * Hook do matching, na transação do evento: registra a decisão do ME. Sem operação correspondente não faz nada
     * (a decisão só existe para um comando emitido por esta cadeia) e devolve false para a métrica.
     */
    @Transactional
    public boolean aplicarDecisao(UUID ordem, String conta, EventoMatching.ResultadoCancelamento decisao) {
        Optional<OperacaoCancelamento> atual = travar(ordem, conta);
        if (atual.isEmpty()) {
            return false;
        }
        EstadoCancelamento estado = atual.get().estado().avancaPara(EstadoCancelamento.DECIDIDO)
                ? EstadoCancelamento.DECIDIDO : atual.get().estado();
        if (atual.get().decisao() == null) {
            em().createNativeQuery("""
                            UPDATE cancelamento_operacao SET estado = :e, decisao = :d, atualizada_em = :t, versao = versao + 1
                            WHERE ordem = :o""")
                    .setParameter("e", estado.name()).setParameter("d", decisao.name())
                    .setParameter("t", OffsetDateTime.now(ZoneOffset.UTC)).setParameter("o", ordem).executeUpdate();
        }
        return true;
    }

    @Override
    @Transactional
    public long pendenteSegundosMax() {
        Object max = em().createNativeQuery("""
                SELECT max(extract(epoch FROM clock_timestamp() - solicitada_em)) FROM cancelamento_operacao
                WHERE estado IN ('AGUARDANDO_RISCO', 'SOLICITADO', 'DECIDIDO')""").getSingleResult();
        return max == null ? 0 : Math.max(0, ((Number) max).longValue());
    }

    private Optional<OperacaoCancelamento> travar(UUID ordem, String conta) {
        return em().createNativeQuery("SELECT " + COLUNAS
                        + " FROM cancelamento_operacao WHERE ordem = :o AND conta = :c FOR UPDATE")
                .setParameter("o", ordem).setParameter("c", conta).getResultList().stream()
                .map(linha -> operacao((Object[]) linha)).findFirst();
    }

    private void atualizar(UUID ordem, EstadoCancelamento estado, String motivo, Instant agora) {
        em().createNativeQuery("""
                        UPDATE cancelamento_operacao SET estado = :e, motivo = :m, atualizada_em = :t, versao = versao + 1
                        WHERE ordem = :o""")
                .unwrap(NativeQuery.class)
                .setParameter("e", estado.name()).setParameter("m", motivo, String.class)
                .setParameter("t", OffsetDateTime.ofInstant(agora, ZoneOffset.UTC)).setParameter("o", ordem)
                .executeUpdate();
    }

    private static OperacaoCancelamento operacao(Object[] c) {
        return new OperacaoCancelamento((UUID) c[0], (String) c[1], EstadoCancelamento.valueOf((String) c[2]),
                c[3] == null ? null : EventoMatching.ResultadoCancelamento.valueOf((String) c[3]), (String) c[4],
                instante(c[5]), instante(c[6]));
    }

    private static Instant instante(Object valor) {
        if (valor instanceof Instant instante) return instante;
        if (valor instanceof OffsetDateTime data) return data.toInstant();
        return ((java.sql.Timestamp) valor).toInstant();
    }
}
