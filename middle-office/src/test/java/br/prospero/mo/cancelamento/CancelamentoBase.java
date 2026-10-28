package br.prospero.mo.cancelamento;

import br.prospero.mo.cancelamento.dominio.SolicitacaoCancelamento;
import br.prospero.mo.cancelamento.helper.EnvelopeCancelamento;
import br.prospero.mo.matching.actor.AplicarMatchingActor;
import br.prospero.mo.matching.helper.EnvelopeMatching;
import br.prospero.mo.mensageria.dominio.MensagemSaida;
import br.prospero.mo.risco.helper.EnvelopeAdmissao;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Cenários de reserva e eventos do ME para os ITs de coordenação do cancelamento (PostgreSQL real, JTA real). */
abstract class CancelamentoBase {
    static final ObjectMapper JSON = new ObjectMapper();
    static final LocalDate SESSAO = LocalDate.of(2026, 10, 18);
    static final String TRACE = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @Inject protected EntityManager em;
    @Inject protected AplicarMatchingActor aplicar;
    @Inject protected br.prospero.mo.mensageria.jpa.OutboxJpa outbox;

    /** Ordem e intenção de uma reserva semeada; compra usa CONTA-002 (caixa) e venda CONTA-001 (custódia). */
    protected record Alvo(UUID ordem, UUID intencao, String conta, boolean compra, long restante) {
        UUID admissao() {
            return EnvelopeAdmissao.id(intencao);
        }

        SolicitacaoCancelamento pedido() {
            return new SolicitacaoCancelamento(UUID.nameUUIDFromBytes(("cancelamento-solicitado:" + ordem).getBytes(StandardCharsets.UTF_8)),
                    ordem, intencao, conta, "ALFA3", SESSAO);
        }
    }

    /** Estado do recibo da admissão semeada. */
    protected enum Recibo { AUSENTE, PENDENTE, CONFIRMADO }

    protected void limpar() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (String tabela : new String[]{"cancelamento_operacao", "outbox", "admissao_publicacao", "liquidacao_expectativa",
                    "liquidacao_aplicada", "liquidacao_aguardando", "liquidacao_recusada", "matching_evento", "matching_negocio",
                    "reserva_perna", "reserva", "decisao_risco"}) {
                em.createNativeQuery("DELETE FROM " + tabela).executeUpdate();
            }
            em.createNativeQuery("UPDATE conta_risco SET total=1000,disponivel=1000,reservado=0").executeUpdate();
            em.createNativeQuery("UPDATE custodia_risco SET total=100,disponivel=100,reservado=0").executeUpdate();
        });
    }

    /** Ordem simples DIA de 10 com {@code restante} ainda aberto; o saldo reservado acompanha o restante. */
    protected Alvo reserva(boolean compra, long restante, Recibo recibo) {
        UUID ordem = UUID.randomUUID(), intencao = UUID.randomUUID();
        String conta = compra ? "CONTA-002" : "CONTA-001";
        QuarkusTransaction.requiringNew().run(() -> {
            decisao(intencao, conta, compra);
            em.createNativeQuery("""
                    INSERT INTO reserva(intencao,conta,ticker,recurso,valor,quantidade,estado,criada_em,ordem,admissao,tipo,validade,
                    data_sessao,quantidade_inicial,quantidade_restante,preco_limite,preco_maximo,valor_restante)
                    VALUES(:i,:c,'ALFA3',:r,:v,:q,:e,now(),:o,:a,'LIMIT','DIA','2026-10-18',10,:rest,10.00,:pm,:vr)
                    """).unwrap(org.hibernate.query.NativeQuery.class).setParameter("i", intencao).setParameter("c", conta)
                    .setParameter("r", compra ? "CAIXA" : "CUSTODIA").setParameter("v", compra ? new BigDecimal("100.00") : null, BigDecimal.class)
                    .setParameter("q", compra ? null : 10L, Long.class).setParameter("e", restante == 10 ? "ATIVA" : "PARCIAL")
                    .setParameter("o", ordem).setParameter("a", EnvelopeAdmissao.id(intencao)).setParameter("rest", restante)
                    .setParameter("pm", compra ? new BigDecimal("10.00") : null, BigDecimal.class)
                    .setParameter("vr", compra ? BigDecimal.valueOf(restante * 10).setScale(2) : null, BigDecimal.class).executeUpdate();
            if (compra) {
                em.createNativeQuery("UPDATE conta_risco SET total=:t,disponivel=:d,reservado=:r WHERE conta='CONTA-002'")
                        .setParameter("t", 1000 - (10 - restante) * 10).setParameter("d", 900).setParameter("r", restante * 10).executeUpdate();
            } else {
                em.createNativeQuery("UPDATE custodia_risco SET total=:t,disponivel=90,reservado=:r WHERE conta='CONTA-001' AND ticker='ALFA3'")
                        .setParameter("t", 100 - (10 - restante)).setParameter("r", restante).executeUpdate();
            }
            if (recibo != Recibo.AUSENTE) recibo(EnvelopeAdmissao.id(intencao), recibo == Recibo.CONFIRMADO);
        });
        return new Alvo(ordem, intencao, conta, compra, restante);
    }

    private void decisao(UUID intencao, String conta, boolean compra) {
        em.createNativeQuery("""
                INSERT INTO decisao_risco(intencao,evento,conta,ticker,lado,resultado,valor_reservado,quantidade_reservada,
                versao_parametros,decidida_em) VALUES(:i,:e,:c,'ALFA3',:l,'RESERVADA',:v,:q,1,now())
                """).unwrap(org.hibernate.query.NativeQuery.class).setParameter("i", intencao).setParameter("e", UUID.randomUUID())
                .setParameter("c", conta).setParameter("l", compra ? "COMPRA" : "VENDA")
                .setParameter("v", compra ? new BigDecimal("100.00") : null, BigDecimal.class)
                .setParameter("q", compra ? null : 10L, Long.class).executeUpdate();
    }

    /** Decisão REJEITADA sem reserva (a ordem nunca teve saldo). */
    protected Alvo rejeitada() {
        UUID ordem = UUID.randomUUID(), intencao = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
                INSERT INTO decisao_risco(intencao,evento,conta,ticker,lado,resultado,motivo,versao_parametros,decidida_em)
                VALUES(:i,:e,'CONTA-001','ALFA3','VENDA','REJEITADA','CUSTODIA_INSUFICIENTE',1,now())
                """).setParameter("i", intencao).setParameter("e", UUID.randomUUID()).executeUpdate());
        return new Alvo(ordem, intencao, "CONTA-001", false, 0);
    }

    /** Reserva admitida antes do V8: sem ordem, sessão nem recibo. */
    protected Alvo legada() {
        UUID intencao = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            decisao(intencao, "CONTA-001", false);
            em.createNativeQuery("INSERT INTO reserva(intencao,conta,ticker,recurso,quantidade,estado,criada_em) VALUES(:i,'CONTA-001','ALFA3','CUSTODIA',10,'ATIVA',now())")
                    .setParameter("i", intencao).executeUpdate();
        });
        return new Alvo(UUID.randomUUID(), intencao, "CONTA-001", false, 10);
    }

    private void recibo(UUID admissao, boolean confirmado) {
        em.createNativeQuery("""
                INSERT INTO admissao_publicacao(admissao,topico,tipo,chave,payload,traceparent,criada_em,confirmada_em)
                VALUES(:a,'matching.comandos.v1','ORDEM_ADMITIDA','ALFA3','{}',NULL,now(),CASE WHEN :c THEN now() END)
                """).setParameter("a", admissao).setParameter("c", confirmado).executeUpdate();
    }

    /** Admissão na outbox real (recibo pendente criado pelo gravador), pronta para o publicador confirmar. */
    protected Alvo reservaComAdmissaoNaOutbox(boolean compra) {
        Alvo alvo = reserva(compra, 10, Recibo.AUSENTE);
        QuarkusTransaction.requiringNew().run(() -> outbox.gravar(new MensagemSaida(alvo.admissao(), "matching.comandos.v1", "ALFA3",
                "ORDEM_ADMITIDA", "{\"ordem\":\"" + alvo.ordem() + "\"}", TRACE, Instant.parse("2026-10-18T12:00:00Z"))));
        return alvo;
    }

    protected Object escalar(String sql, Object... parametros) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var q = em.createNativeQuery(sql);
            for (int i = 0; i < parametros.length; i++) q.setParameter(i + 1, parametros[i]);
            return q.getSingleResult();
        });
    }

    protected long contar(String tabela, String where, Object... parametros) {
        return ((Number) escalar("SELECT count(*) FROM " + tabela + " WHERE " + where, parametros)).longValue();
    }

    protected long emitidos(String tipo, Alvo alvo) {
        return contar("outbox", "tipo=?1 AND payload LIKE ?2", tipo, "%" + alvo.ordem() + "%");
    }

    protected String estado(Alvo alvo) {
        List<?> linhas = QuarkusTransaction.requiringNew().call(() ->
                em.createNativeQuery("SELECT estado||':'||coalesce(motivo,'')||':'||coalesce(decisao,'') FROM cancelamento_operacao WHERE ordem=:o")
                        .setParameter("o", alvo.ordem()).getResultList());
        return linhas.isEmpty() ? "-" : linhas.getFirst().toString();
    }

    /** total/disponivel/reservado do recurso da reserva. */
    protected String saldo(Alvo alvo) {
        return escalar(alvo.compra()
                ? "SELECT total||'/'||disponivel||'/'||reservado FROM conta_risco WHERE conta='CONTA-002'"
                : "SELECT total||'/'||disponivel||'/'||reservado FROM custodia_risco WHERE conta='CONTA-001' AND ticker='ALFA3'").toString();
    }

    protected String estadoReserva(Alvo alvo) {
        return escalar("SELECT estado||':'||quantidade_restante FROM reserva WHERE intencao=?1", alvo.intencao()).toString();
    }

    protected static UUID derivado(String semente) {
        return UUID.nameUUIDFromBytes(semente.getBytes(StandardCharsets.UTF_8));
    }

    /** Terminal CANCELADA_MANUAL do ME (índice 0 do comando), com os campos informados. */
    protected static byte[] terminal(Alvo alvo, long executada, long cancelada) {
        return evento(alvo, "ORDEM_ENCERRADA", 0, d -> {
            d.put("motivo", "CANCELADA_MANUAL");
            d.put("executada", executada);
            d.put("cancelada", cancelada);
        });
    }

    /** Decisão do ME: APLICADO (índice 1, com o terminal derivado) ou uma das três recusas (índice 0). */
    protected static byte[] decidido(Alvo alvo, String resultado) {
        boolean aplicado = resultado.equals("APLICADO");
        UUID comando = EnvelopeCancelamento.comando(alvo.ordem());
        return evento(alvo, "CANCELAMENTO_DECIDIDO", aplicado ? 1 : 0, d -> {
            d.put("resultado", resultado);
            if (aplicado) d.put("terminal", derivado("matching:" + comando + ":0").toString());
            else d.putNull("terminal");
        });
    }

    private static byte[] evento(Alvo alvo, String tipo, int indice, java.util.function.Consumer<ObjectNode> dados) {
        UUID comando = EnvelopeCancelamento.comando(alvo.ordem());
        ObjectNode e = JSON.createObjectNode();
        e.put("id", derivado("matching:" + comando + ":" + indice).toString());
        e.put("versao", 5);
        e.put("origem", "matching-engine");
        e.put("correlacao", derivado("admissao:" + alvo.intencao()).toString());
        e.put("ocorridoEm", "2026-10-18T12:00:00Z");
        e.put("ticker", "ALFA3");
        e.put("dataSessao", "2026-10-18");
        e.putNull("traceparent");
        e.put("causacao", comando.toString());
        ObjectNode d = e.putObject("dados");
        d.put("ordem", alvo.ordem().toString());
        d.put("intencao", alvo.intencao().toString());
        d.put("conta", alvo.conta());
        dados.accept(d);
        e.put("tipo", tipo);
        return e.toString().getBytes(StandardCharsets.UTF_8);
    }

    protected void aplicarEvento(byte[] bytes) {
        aplicar.aplicar(EnvelopeMatching.ler(bytes));
    }
}
