package br.prospero.fo.ordens.jpa;

import br.prospero.fo.ordens.dominio.MarcoCancelamento;
import br.prospero.fo.ordens.dominio.EstadoOrdem;
import br.prospero.fo.ordens.dominio.EventoContabilizacao;
import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.dominio.MarcoTrajetoria;
import br.prospero.fo.ordens.dominio.MatchingIncoerenteException;
import br.prospero.fo.ordens.dominio.Ordem;
import br.prospero.fo.ordens.dominio.PaginaTrajetoria;
import br.prospero.fo.ordens.dominio.RepositorioTrajetoria;
import br.prospero.fo.ordens.dominio.ResultadoRisco;
import br.prospero.fo.ordens.dominio.TrajetoriaIndisponivelException;
import br.prospero.fo.comum.dominio.MensagemSaida;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.query.NativeQuery;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Revisões são serializadas por conta até o commit, depois dos locks financeiros. */
@ApplicationScoped
public class RepositorioTrajetoriaJpa implements RepositorioTrajetoria {
    private final Instance<EntityManager> gerenciadores;

    @Inject
    public RepositorioTrajetoriaJpa(Instance<EntityManager> gerenciadores) {
        this.gerenciadores = gerenciadores;
    }

    private EntityManager em() { return gerenciadores.get(); }

    @Override
    @Transactional
    public Optional<PaginaTrajetoria> consultar(String conta, UUID ordem, long apos, int limite) {
        List<?> identidade = em().createNativeQuery("SELECT 1 FROM ordem WHERE id=:o AND conta=:c")
                .setParameter("o", ordem).setParameter("c", conta).getResultList();
        if (identidade.isEmpty()) return Optional.empty();
        if ((Boolean) em().createNativeQuery("SELECT EXISTS(SELECT 1 FROM mensagem_quarentena WHERE topico IN (:topicos) AND resolvido_em IS NULL)")
                .setParameter("topicos", List.of("matching.eventos.v1", "acompanhamento.eventos.v1", "risco.resultados.v1")).getSingleResult()) {
            throw new TrajetoriaIndisponivelException();
        }
        // Metadados e página partilham snapshot de uma única instrução PostgreSQL.
        List<?> linhas = em().createNativeQuery("""
                SELECT r.revisao, EXISTS(SELECT 1 FROM trajetoria_marco h
                    WHERE h.conta=:c AND h.ordem=:o AND h.tipo='ORDEM_RECEBIDA'),
                    (SELECT min(h.observado_em) FROM trajetoria_marco h WHERE h.conta=:c AND h.ordem=:o),
                    m.sequencia,m.origem,m.tipo,m.evento,m.causacao,m.negocio,m.ocorrido_em,m.observado_em,
                    m.quantidade,m.motivo,m.trace_id
                FROM trajetoria_revisao r LEFT JOIN LATERAL (
                    SELECT * FROM trajetoria_marco WHERE conta=:c AND ordem=:o AND sequencia>:apos
                    ORDER BY sequencia LIMIT :limite) m ON true WHERE r.conta=:c ORDER BY m.sequencia
                """).setParameter("c", conta).setParameter("o", ordem).setParameter("apos", apos)
                .setParameter("limite", limite).getResultList();
        if (linhas.isEmpty()) return Optional.of(new PaginaTrajetoria(0, false, null, List.of()));
        Object[] primeira = (Object[]) linhas.getFirst();
        List<MarcoTrajetoria> eventos = linhas.stream().map(l -> (Object[]) l).filter(l -> l[3] != null)
                .map(l -> new MarcoTrajetoria(((Number) l[3]).longValue(), (String) l[4], (String) l[5],
                        (UUID) l[6], (UUID) l[7], (String) l[8], instante(l[9]), instante(l[10]),
                        (String) l[11], (String) l[12], (String) l[13])).toList();
        return Optional.of(new PaginaTrajetoria(((Number) primeira[0]).longValue(), (Boolean) primeira[1],
                instante(primeira[2]), eventos));
    }

    /** Chamado no final da transação de ordem/outbox. */
    public void registrarRecebimento(List<Ordem> ordens, MensagemSaida comando) {
        travar(ordens.stream().map(Ordem::conta).toList());
        for (Ordem o : ordens) {
            UUID evento = UUID.nameUUIDFromBytes(("recebimento:" + o.id()).getBytes(StandardCharsets.UTF_8));
            gravar(o.conta(), o.id(), new NovoMarco("front-office", "ORDEM_RECEBIDA", evento,
                    EnvelopeMatching.assinatura(comando.payload().getBytes(StandardCharsets.UTF_8)), null, null,
                    o.recebidaEm(), Long.toString(o.quantidade()), null, comando.traceparent()));
        }
    }

    /** Registra fato MO mesmo quando sua chegada tardia não altera mais o estado financeiro. */
    public void registrarRisco(ResultadoRisco r) {
        List<OrdemEntity> ordens = em().createQuery(
                "from OrdemEntity where conta=:c and grupoEnvio=:g order by id", OrdemEntity.class)
                .setParameter("c", r.conta()).setParameter("g", r.grupoEnvio()).getResultList();
        if (ordens.isEmpty()) return;
        travar(List.of(r.conta()));
        String assinatura = r.assinatura() == null ? EnvelopeMatching.assinatura(
                (r.evento() + ":" + r.grupoEnvio() + ":" + r.conta() + ":" + r.estado() + ":" + r.motivo())
                        .getBytes(StandardCharsets.UTF_8)) : r.assinatura();
        for (OrdemEntity o : ordens) {
            gravar(o.conta, o.id, new NovoMarco("middle-office", r.estado() == EstadoOrdem.RESERVADA ? "RISCO_RESERVADO" : "RISCO_REJEITADO",
                    r.evento(), assinatura, r.grupoEnvio(), null, r.ocorridoEm(), null,
                    r.motivo() == null ? null : r.motivo().name(), r.traceparent()));
        }
    }

    /** Marco do cancelamento manual (solicitado, confirmado ou recusado), serializado pelo lock de revisão da conta. */
    public void registrarCancelamento(String conta, UUID ordem, MarcoCancelamento marco) {
        travar(List.of(conta));
        gravar(conta, ordem, new NovoMarco(marco.origem(), marco.tipo(), marco.evento(), marco.assinatura(), null, null,
                marco.ocorridoEm(), null, marco.motivo(), marco.traceparent()));
    }

    public void registrarMatching(EventoMatching e, List<OrdemEntity> ordens) {
        if (ordens.isEmpty()) return;
        travar(ordens.stream().map(o -> o.conta).toList());
        String tipo = switch (e.detalhe()) {
            case EventoMatching.Aceita a -> "ORDEM_ACEITA";
            case EventoMatching.Oferta o -> "OFERTA_NO_LIVRO";
            case EventoMatching.Rejeitada r -> "ORDEM_REJEITADA";
            case EventoMatching.Encerrada f -> "ORDEM_ENCERRADA";
            case EventoMatching.Negocio n -> "NEGOCIO";
            case EventoMatching.StopPendente p -> "STOP_PENDENTE";
            case EventoMatching.StopAcionado a -> "STOP_ACIONADO";
            case EventoMatching.Reduzida r -> "ORDEM_REDUZIDA";
            case EventoMatching.SessaoEncerrada s -> "SESSAO_ENCERRADA";
            case EventoMatching.CancelamentoDecidido d -> "CANCELAMENTO_DECIDIDO";
        };
        String negocio = e.detalhe() instanceof EventoMatching.Negocio n ? n.id() : null;
        String quantidade = e.detalhe() instanceof EventoMatching.Negocio n ? Long.toString(n.quantidade()) : null;
        String motivo = switch (e.detalhe()) {
            case EventoMatching.Rejeitada r -> r.motivo().name();
            case EventoMatching.Encerrada f -> f.motivo();
            case EventoMatching.CancelamentoDecidido d -> d.resultado().name();
            default -> null;
        };
        for (OrdemEntity o : ordens) {
            gravar(o.conta, o.id, new NovoMarco("matching-engine", tipo, e.id(), e.assinatura(), e.causacao(), negocio,
                    e.ocorridoEm(), quantidade, motivo, e.traceparent()));
        }
    }

    @Transactional
    public boolean registrarContabilizacao(EventoContabilizacao evento) {
        exigirOrdem(evento.ordemCompra(), evento.contaCompra(), evento.ticker(), "COMPRA", evento.quantidade());
        exigirOrdem(evento.ordemVenda(), evento.contaVenda(), evento.ticker(), "VENDA", evento.quantidade());
        List<String> contas = List.of("CONTA-001", "CONTA-002", "CONTA-003", "CONTA-004", "CONTA-005");
        travar(contas);
        List<?> anterior = em().createNativeQuery("SELECT assinatura FROM trajetoria_identidade WHERE origem='back-office' AND evento=:e")
                .setParameter("e", evento.id()).getResultList();
        if (!anterior.isEmpty()) {
            if (!evento.assinatura().equals(anterior.getFirst().toString().trim())) {
                throw new MatchingIncoerenteException("TRAJETORIA_IDENTIDADE_CONFLITANTE");
            }
            return false;
        }
        gravar(evento.contaCompra(), evento.ordemCompra(), new NovoMarco("back-office", "NEGOCIO_CONTABILIZADO", evento.id(),
                evento.assinatura(), evento.causacao(), evento.negocio(), evento.ocorridoEm(),
                Long.toString(evento.quantidade()), null, evento.traceparent()));
        gravar(evento.contaVenda(), evento.ordemVenda(), new NovoMarco("back-office", "NEGOCIO_CONTABILIZADO", evento.id(),
                evento.assinatura(), evento.causacao(), evento.negocio(), evento.ocorridoEm(),
                Long.toString(evento.quantidade()), null, evento.traceparent()));
        // Cotação BO altera PnL inclusive das contas que não participaram; aviso sem detalhes financeiros.
        for (String conta : contas) {
            if (!conta.equals(evento.contaCompra()) && !conta.equals(evento.contaVenda())) {
                em().createNativeQuery("UPDATE trajetoria_revisao SET revisao=revisao+1 WHERE conta=:c")
                        .setParameter("c", conta).executeUpdate();
            }
        }
        return true;
    }

    private void exigirOrdem(UUID ordem, String conta, String ticker, String lado, long quantidade) {
        if (em().createNativeQuery("SELECT 1 FROM ordem WHERE id=:o AND conta=:c AND ticker=:t AND lado=:l AND quantidade>=:q")
                .setParameter("o", ordem).setParameter("c", conta).setParameter("t", ticker)
                .setParameter("l", lado).setParameter("q", quantidade).getResultList().isEmpty()) {
            throw new MatchingIncoerenteException("CONTABILIZACAO_ORDEM_DIVERGENTE");
        }
    }

    private void travar(List<String> contas) {
        // Força escrita das entidades geridas antes do lock de revisão (mesma ordem usada pelo risco/matching).
        em().flush();
        for (String conta : contas.stream().distinct().sorted().toList()) {
            em().createNativeQuery("SELECT revisao FROM trajetoria_revisao WHERE conta=:c FOR UPDATE")
                    .setParameter("c", conta).getSingleResult();
        }
    }

    private record NovoMarco(String origem, String tipo, UUID evento, String assinatura, UUID causacao,
                             String negocio, Instant ocorridoEm, String quantidade, String motivo, String traceparent) { }

    private void gravar(String conta, UUID ordem, NovoMarco marco) {
        String origem = marco.origem();
        String tipo = marco.tipo();
        UUID evento = marco.evento();
        String assinatura = marco.assinatura();
        UUID causacao = marco.causacao();
        String negocio = marco.negocio();
        Instant ocorridoEm = marco.ocorridoEm();
        String quantidade = marco.quantidade();
        String motivo = marco.motivo();
        String traceparent = marco.traceparent();
        em().createNativeQuery("""
                INSERT INTO trajetoria_identidade(origem,evento,assinatura) VALUES(:origem,:e,:h)
                ON CONFLICT (origem,evento) DO NOTHING
                """).setParameter("origem", origem).setParameter("e", evento).setParameter("h", assinatura).executeUpdate();
        String identidade = em().createNativeQuery("SELECT assinatura FROM trajetoria_identidade WHERE origem=:origem AND evento=:e")
                .setParameter("origem", origem).setParameter("e", evento).getSingleResult().toString().trim();
        if (!assinatura.equals(identidade)) throw new MatchingIncoerenteException("TRAJETORIA_IDENTIDADE_CONFLITANTE");
        List<?> anterior = em().createNativeQuery("""
                SELECT assinatura FROM trajetoria_marco WHERE ordem=:o AND origem=:origem AND evento=:e
                """).setParameter("o", ordem).setParameter("origem", origem).setParameter("e", evento).getResultList();
        if (!anterior.isEmpty()) {
            if (!assinatura.equals(anterior.getFirst().toString().trim())) {
                throw new MatchingIncoerenteException("TRAJETORIA_IDENTIDADE_CONFLITANTE");
            }
            return;
        }
        long sequencia = ((Number) em().createNativeQuery("""
                UPDATE trajetoria_revisao SET revisao=revisao+1 WHERE conta=:c RETURNING revisao
                """).setParameter("c", conta).getSingleResult()).longValue();
        String traceId = traceparent == null ? null : traceparent.substring(3, 35);
        em().createNativeQuery("""
                INSERT INTO trajetoria_marco(conta,sequencia,ordem,origem,tipo,evento,assinatura,causacao,negocio,
                    ocorrido_em,quantidade,motivo,trace_id)
                VALUES (:c,:s,:o,:origem,:tipo,:e,:h,:causa,:n,:t,:q,:m,:trace)
                """).unwrap(NativeQuery.class).setParameter("c", conta).setParameter("s", sequencia)
                .setParameter("o", ordem).setParameter("origem", origem).setParameter("tipo", tipo)
                .setParameter("e", evento).setParameter("h", assinatura).setParameter("causa", causacao, UUID.class)
                .setParameter("n", negocio, String.class)
                .setParameter("t", ocorridoEm == null ? null : OffsetDateTime.ofInstant(ocorridoEm, ZoneOffset.UTC), OffsetDateTime.class)
                .setParameter("q", quantidade, String.class).setParameter("m", motivo, String.class)
                .setParameter("trace", traceId, String.class).executeUpdate();
    }

    private static Instant instante(Object valor) {
        if (valor == null) return null;
        if (valor instanceof Instant instante) return instante;
        if (valor instanceof OffsetDateTime data) return data.toInstant();
        return ((java.sql.Timestamp) valor).toInstant();
    }
}
