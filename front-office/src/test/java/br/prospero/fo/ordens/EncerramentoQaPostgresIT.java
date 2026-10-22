package br.prospero.fo.ordens;

import br.prospero.fo.ordens.dominio.EventoMatching;
import br.prospero.fo.ordens.dominio.MatchingIncoerenteException;
import br.prospero.fo.ordens.helper.EnvelopeMatching;
import br.prospero.fo.ordens.jpa.RepositorioMatchingJpa;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** QA independente da RF05c.4a no FO: expiração v4 persistida, marco SESSAO_ENCERRADA e identidade. Opt-in. */
@QuarkusTest
@TestProfile(MatchingPostgresIT.Banco.class)
class EncerramentoQaPostgresIT {
    private static final UUID ENCERRAMENTO = encerramento("ALFA3", "2026-10-18");

    @AfterAll
    static void removerSchemaProprio() throws Exception {
        MatchingPostgresIT.removerSchemaProprio();
    }

    @Inject RepositorioMatchingJpa repositorio;
    @Inject EntityManager em;

    private static UUID encerramento(String ticker, String data) {
        return UUID.nameUUIDFromBytes(("encerramento:" + ticker + ":" + data).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID admissao(UUID intencao) {
        return UUID.nameUUIDFromBytes(("admissao:" + intencao).getBytes(StandardCharsets.UTF_8));
    }

    private UUID ordem(String tipo, String validade, String estado, UUID grupoOco, String papel) {
        UUID id = UUID.randomUUID();
        boolean stop = tipo.startsWith("STOP");
        UUID envio = grupoOco == null ? id : grupoOco;
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
                INSERT INTO ordem(id,conta,ticker,lado,tipo,validade,quantidade,preco_limite,preco_gatilho,grupo_oco,papel_oco,
                grupo_envio,estado,data_sessao,recebida_em,admissao_matching)
                VALUES(:i,'CONTA-003','ALFA3','VENDA',:t,:v,10,:p,:g,:o,:papel,:e,:s,'2026-10-18',now(),:a)
                """).unwrap(org.hibernate.query.NativeQuery.class).setParameter("i", id).setParameter("t", tipo)
                .setParameter("v", validade).setParameter("s", estado)
                .setParameter("p", tipo.equals("STOP") ? null : new BigDecimal("9.00"), BigDecimal.class)
                .setParameter("g", stop ? new BigDecimal("9.50") : null, BigDecimal.class)
                .setParameter("o", grupoOco, UUID.class).setParameter("papel", papel, String.class)
                .setParameter("e", envio).setParameter("a", admissao(envio)).executeUpdate());
        return id;
    }

    private static byte[] bytesExpirada(UUID ordem, UUID intencao, String conta, UUID causacao, long cancelada) {
        // Ordem dos campos como o EnvelopeMatching do ME serializa hoje (tipo após dados).
        return """
                {"id":"%s","versao":4,"origem":"matching-engine","correlacao":"%s","ocorridoEm":"2026-10-18T21:00:00Z",\
                "ticker":"ALFA3","dataSessao":"2026-10-18","traceparent":null,"causacao":"%s","dados":{"ordem":"%s",\
                "intencao":"%s","conta":"%s","motivo":"EXPIRADA_DIA","executada":0,"cancelada":%d},"tipo":"ORDEM_ENCERRADA"}"""
                .formatted(UUID.randomUUID(), admissao(intencao), causacao, ordem, intencao, conta, cancelada)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static EventoMatching expirada(UUID ordem, UUID intencao, String conta, UUID causacao, long cancelada) {
        return EnvelopeMatching.evento(bytesExpirada(ordem, intencao, conta, causacao, cancelada), "ALFA3");
    }

    private static EventoMatching marco(UUID correlacao, UUID causacao, String ticker, String data) {
        String s = """
                {"id":"%s","versao":4,"origem":"matching-engine","correlacao":"%s","ocorridoEm":"2026-10-18T21:00:00Z",\
                "ticker":"%s","dataSessao":"%s","traceparent":null,"causacao":"%s","dados":{"expiradas":2},\
                "tipo":"SESSAO_ENCERRADA"}""".formatted(UUID.randomUUID(), correlacao, ticker, data, causacao);
        return EnvelopeMatching.evento(s.getBytes(StandardCharsets.UTF_8), ticker);
    }

    private String coluna(String coluna, UUID ordem) {
        return QuarkusTransaction.requiringNew().call(() -> String.valueOf(em.createNativeQuery(
                "SELECT " + coluna + " FROM ordem WHERE id=:i").setParameter("i", ordem).getSingleResult()));
    }

    @Test
    void stopSimplesPendenteEStopLimitAcionadoExpiramUmaVez() {
        UUID pendente = ordem("STOP", "DIA", "AGUARDANDO_GATILHO", null, null);
        EventoMatching fim = expirada(pendente, pendente, "CONTA-003", ENCERRAMENTO, 10);
        assertTrue(repositorio.aplicar(fim));
        assertFalse(repositorio.aplicar(fim), "reentrega idempotente");
        assertEquals("CANCELADA", coluna("estado", pendente));
        assertEquals("EXPIRADA_DIA", coluna("motivo_encerramento", pendente));
        assertEquals("10", coluna("quantidade_cancelada", pendente));
        // Projeção tolera o mesmo terminal com id novo sem mudar nada (o MO é quem recusa liberar de novo).
        repositorio.aplicar(expirada(pendente, pendente, "CONTA-003", ENCERRAMENTO, 10));
        assertEquals("10", coluna("quantidade_cancelada", pendente));
        assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(
                expirada(pendente, pendente, "CONTA-003", ENCERRAMENTO, 9)), "terminal divergente após expiração");

        UUID acionado = ordem("STOP_LIMIT", "DIA", "ABERTA", null, null);
        assertTrue(repositorio.aplicar(expirada(acionado, acionado, "CONTA-003", ENCERRAMENTO, 10)));
        assertEquals("CANCELADA", coluna("estado", acionado));
    }

    @Test
    void expiracaoDeIocOuComIdentidadeAlheiaERecusada() {
        UUID ioc = ordem("LIMIT", "IOC", "ABERTA", null, null);
        assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(
                expirada(ioc, ioc, "CONTA-003", ENCERRAMENTO, 10)), "IOC não expira por DIA");
        UUID dia = ordem("LIMIT", "DIA", "ABERTA", null, null);
        assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(
                expirada(dia, dia, "CONTA-002", ENCERRAMENTO, 10)), "conta de outra ordem");
        assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(
                expirada(dia, dia, "CONTA-003", encerramento("ALFA3", "2026-10-19"), 10)), "causação de outra data");
        assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(
                expirada(dia, dia, "CONTA-003", admissao(dia), 10)), "causação = admissão, não o encerramento");
        assertEquals("ABERTA", coluna("estado", ioc));
        assertEquals("ABERTA", coluna("estado", dia));
    }

    @Test
    void pernasOcoExpiramEmV4() {
        UUID grupo = UUID.randomUUID();
        UUID tp = ordem("LIMIT", "DIA", "ABERTA", grupo, "TAKE_PROFIT");
        UUID sl = ordem("STOP", "DIA", "AGUARDANDO_GATILHO", grupo, "STOP_LOSS");
        assertTrue(repositorio.aplicar(expirada(tp, grupo, "CONTA-003", ENCERRAMENTO, 10)));
        assertTrue(repositorio.aplicar(expirada(sl, grupo, "CONTA-003", ENCERRAMENTO, 10)));
        assertEquals("CANCELADA", coluna("estado", tp));
        assertEquals("CANCELADA", coluna("estado", sl));
    }

    @Test
    void marcoSessaoEncerradaRegistraUmaVezERecusaIdentidadeDivergente() {
        EventoMatching valido = marco(ENCERRAMENTO, ENCERRAMENTO, "ALFA3", "2026-10-18");
        assertTrue(repositorio.aplicar(valido));
        assertFalse(repositorio.aplicar(valido), "reentrega idempotente");
        assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(
                marco(UUID.randomUUID(), ENCERRAMENTO, "ALFA3", "2026-10-18")), "correlação != causação");
        UUID beta = encerramento("BETA3", "2026-10-18");
        assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(
                marco(beta, beta, "ALFA3", "2026-10-18")), "causação de outro ativo");
        assertTrue(repositorio.aplicar(marco(beta, beta, "BETA3", "2026-10-18")), "marco de outro ativo é independente");
        EventoMatching conflitante = new EventoMatching(valido.id(), valido.correlacao(), valido.ticker(), valido.sessao(),
                "outra-assinatura", valido.detalhe(), valido.causacao(), 4);
        assertThrows(MatchingIncoerenteException.class, () -> repositorio.aplicar(conflitante), "mesmo id com bytes divergentes");
    }
}
