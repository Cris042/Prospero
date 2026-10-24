package br.prospero.mo.liquidacao;

import br.prospero.mo.comum.dominio.RegraNegocioException;
import br.prospero.mo.liquidacao.actor.AplicarLiquidacaoActor;
import br.prospero.mo.liquidacao.dominio.Desfecho;
import br.prospero.mo.liquidacao.dominio.LadoLiquidacao;
import br.prospero.mo.liquidacao.dominio.MotivoRecusa;
import br.prospero.mo.liquidacao.dominio.RegistroLiquidacao;
import br.prospero.mo.liquidacao.kafka.ConsumidorLiquidacao;
import br.prospero.mo.matching.actor.AplicarMatchingActor;
import br.prospero.mo.matching.helper.EnvelopeMatching;
import br.prospero.mo.mensageria.kafka.CicloConsumo;
import br.prospero.mo.risco.actor.CarteiraActor;
import br.prospero.mo.risco.dto.PreparacaoCarteiraRequestDTO;
import br.prospero.mo.risco.helper.EnvelopeAdmissao;
import br.prospero.mo.risco.jpa.RepositorioRiscoJpa;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static br.prospero.mo.liquidacao.EventosSinteticos.JSON;
import static br.prospero.mo.liquidacao.EventosSinteticos.bytes;
import static br.prospero.mo.liquidacao.EventosSinteticos.evento;
import static org.junit.jupiter.api.Assertions.*;

/**
 * RF08c (ADR-016) em PostgreSQL real, com eventos sintéticos (ainda sem emissor no BO). Opt-in -Dtest=LiquidacaoPostgresIT;
 * banco descartável informado pelas variáveis MATCHING_TEST_DB_*. Teto do aguardando reduzido a 3.
 */
@QuarkusTest
@TestProfile(LiquidacaoPostgresIT.Banco.class)
class LiquidacaoPostgresIT {
    private static final String SCHEMA = schemaDaExecucao();
    private static final String NEGOCIO = "ALFA3-2026-10-18-1";
    private static final UUID CAUSA = UUID.randomUUID();

    private static String schemaDaExecucao() {
        synchronized (System.getProperties()) {
            return System.getProperties().computeIfAbsent("prospero.it.mo.liquidacao.schema",
                    chave -> "mo_liquidacao_it_" + UUID.randomUUID().toString().replace("-", "")).toString();
        }
    }

    public static class Banco implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            try (var c = conexao(); var s = c.createStatement()) {
                s.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
            } catch (Exception erro) {
                throw new IllegalStateException("Banco descartável obrigatório", erro);
            }
            return Map.of("quarkus.datasource.active", "true", "quarkus.datasource.jdbc.url",
                    System.getenv("MATCHING_TEST_DB_URL") + "?currentSchema=" + SCHEMA,
                    "quarkus.datasource.username", System.getenv("MATCHING_TEST_DB_USER"),
                    "quarkus.datasource.password", System.getenv("MATCHING_TEST_DB_PASSWORD"),
                    "quarkus.datasource.jdbc.max-size", "8",
                    "quarkus.flyway.migrate-at-start", "true", "quarkus.flyway.schemas", SCHEMA,
                    "prospero.liquidacao.aguardando.teto", "3");
        }
    }

    private static java.sql.Connection conexao() throws Exception {
        return DriverManager.getConnection(System.getenv("MATCHING_TEST_DB_URL"),
                System.getenv("MATCHING_TEST_DB_USER"), System.getenv("MATCHING_TEST_DB_PASSWORD"));
    }

    @AfterAll
    static void removerSchemaProprio() throws Exception {
        try (var c = conexao(); var s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Inject AplicarMatchingActor matching;
    @Inject AplicarLiquidacaoActor liquidacao;
    @Inject EntityManager em;
    @Inject RepositorioRiscoJpa risco;

    private record Par(UUID compra, UUID venda) { }

    private long deslocamento;

    @BeforeEach
    void limpar() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (String tabela : new String[]{"liquidacao_recusada", "liquidacao_aguardando", "liquidacao_aplicada",
                    "liquidacao_expectativa", "matching_evento", "matching_negocio", "reserva_perna", "reserva",
                    "decisao_risco"}) {
                em.createNativeQuery("DELETE FROM " + tabela).executeUpdate();
            }
            em.createNativeQuery("UPDATE conta_risco SET total=1000,disponivel=1000,reservado=0").executeUpdate();
            em.createNativeQuery("UPDATE custodia_risco SET total=100,disponivel=100,reservado=0").executeUpdate();
        });
    }

    /** Reservas de compra (caixa 100,00 a 10,00) e venda (10 ALFA3), como no MatchingPostgresIT. */
    private Par reservas(String contaCompra, String contaVenda) {
        Par par = new Par(UUID.randomUUID(), UUID.randomUUID());
        QuarkusTransaction.requiringNew().run(() -> {
            reserva(par.compra(), contaCompra, "COMPRA");
            reserva(par.venda(), contaVenda, "VENDA");
            em.createNativeQuery("UPDATE conta_risco SET disponivel=disponivel-100,reservado=reservado+100 WHERE conta=:c")
                    .setParameter("c", contaCompra).executeUpdate();
            em.createNativeQuery("UPDATE custodia_risco SET disponivel=disponivel-10,reservado=reservado+10 WHERE conta=:c AND ticker='ALFA3'")
                    .setParameter("c", contaVenda).executeUpdate();
        });
        return par;
    }

    private void reserva(UUID id, String conta, String lado) {
        boolean compra = lado.equals("COMPRA");
        em.createNativeQuery("""
                INSERT INTO decisao_risco(intencao,evento,conta,ticker,lado,resultado,valor_reservado,
                quantidade_reservada,versao_parametros,decidida_em)
                VALUES(:i,:e,:c,'ALFA3',:l,'RESERVADA',:v,:q,1,now())
                """).unwrap(org.hibernate.query.NativeQuery.class).setParameter("i", id).setParameter("e", UUID.randomUUID())
                .setParameter("c", conta).setParameter("l", lado)
                .setParameter("v", compra ? new BigDecimal("100.00") : null, BigDecimal.class)
                .setParameter("q", compra ? null : 10L, Long.class).executeUpdate();
        em.createNativeQuery("""
                INSERT INTO reserva(intencao,conta,ticker,recurso,valor,quantidade,estado,criada_em,
                ordem,admissao,tipo,validade,data_sessao,quantidade_inicial,quantidade_restante,
                preco_limite,preco_maximo,valor_restante)
                VALUES(:i,:c,'ALFA3',:r,:v,:q,'ATIVA',now(),:i,:a,'LIMIT','IOC','2026-10-18',10,10,:p,:t,:v)
                """).unwrap(org.hibernate.query.NativeQuery.class).setParameter("i", id).setParameter("c", conta)
                .setParameter("r", compra ? "CAIXA" : "CUSTODIA")
                .setParameter("v", compra ? new BigDecimal("100.00") : null, BigDecimal.class)
                .setParameter("q", compra ? null : 10L, Long.class).setParameter("a", EnvelopeAdmissao.id(id))
                .setParameter("p", new BigDecimal(compra ? "10.00" : "9.00"))
                .setParameter("t", compra ? new BigDecimal("10.00") : null, BigDecimal.class).executeUpdate();
    }

    /** NEGOCIO de 4 ALFA3 a 9,00 (36,00), com id de evento igual à causação usada nos eventos de liquidação. */
    private void negocio(Par par, String negocio, String contaCompra, String contaVenda) throws Exception {
        ObjectNode e = JSON.createObjectNode();
        // Negócio padrão usa CAUSA; os demais, id próprio (a deduplicação do matching é por id do evento).
        e.put("id", (negocio.equals(NEGOCIO) ? CAUSA : UUID.nameUUIDFromBytes(negocio.getBytes(StandardCharsets.UTF_8))).toString());
        e.put("tipo", "NEGOCIO"); e.put("versao", 1);
        e.put("origem", "matching-engine"); e.put("correlacao", EnvelopeAdmissao.id(par.compra()).toString());
        e.put("ocorridoEm", "2026-10-18T12:00:00Z"); e.put("ticker", "ALFA3");
        e.put("dataSessao", "2026-10-18"); e.putNull("traceparent");
        ObjectNode d = e.putObject("dados");
        d.put("negocio", negocio); d.put("preco", "9.00"); d.put("quantidade", 4);
        d.put("ordemCompra", par.compra().toString()); d.put("intencaoCompra", par.compra().toString());
        d.put("contaCompra", contaCompra); d.put("ordemVenda", par.venda().toString());
        d.put("intencaoVenda", par.venda().toString()); d.put("contaVenda", contaVenda); d.put("agressor", "COMPRA");
        matching.aplicar(EnvelopeMatching.ler(JSON.writeValueAsBytes(e)));
    }

    private Par negocioPadrao() throws Exception {
        Par par = reservas("CONTA-002", "CONTA-003");
        negocio(par, NEGOCIO, "CONTA-002", "CONTA-003");
        return par;
    }

    private static ObjectNode vendedor() {
        return evento(NEGOCIO, LadoLiquidacao.VENDEDOR, "CONTA-003", "ALFA3", "36.00", CAUSA);
    }

    private static ObjectNode comprador() {
        return evento(NEGOCIO, LadoLiquidacao.COMPRADOR, "CONTA-002", "ALFA3", "4", CAUSA);
    }

    private Desfecho enviar(String chave, byte[] valor) {
        return liquidacao.aplicar(new RegistroLiquidacao(chave, valor, 0, deslocamento++));
    }

    private Desfecho enviar(ObjectNode evento) {
        return enviar(evento.get("dados").get("conta").textValue(), bytes(evento));
    }

    private long numero(String sql) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(sql).getSingleResult()).longValue());
    }

    private BigDecimal decimal(String sql) {
        return QuarkusTransaction.requiringNew().call(() -> new BigDecimal(em.createNativeQuery(sql).getSingleResult().toString()));
    }

    private void caixa(String conta, String total, String disponivel, String reservado) {
        Object[] c = QuarkusTransaction.requiringNew().call(() -> (Object[]) em.createNativeQuery(
                "SELECT total, disponivel, reservado FROM conta_risco WHERE conta=:c").setParameter("c", conta).getSingleResult());
        assertEquals(0, new BigDecimal(total).compareTo((BigDecimal) c[0]), conta + " total");
        assertEquals(0, new BigDecimal(disponivel).compareTo((BigDecimal) c[1]), conta + " disponivel");
        assertEquals(0, new BigDecimal(reservado).compareTo((BigDecimal) c[2]), conta + " reservado");
    }

    private void custodia(String conta, long total, long disponivel) {
        Object[] c = QuarkusTransaction.requiringNew().call(() -> (Object[]) em.createNativeQuery(
                "SELECT total, disponivel FROM custodia_risco WHERE conta=:c AND ticker='ALFA3'").setParameter("c", conta).getSingleResult());
        assertEquals(total, ((Number) c[0]).longValue(), conta + " custodia total");
        assertEquals(disponivel, ((Number) c[1]).longValue(), conta + " custodia disponivel");
    }

    private List<String> motivosRecusa() {
        return QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(
                "SELECT motivo FROM liquidacao_recusada ORDER BY particao, deslocamento").getResultList()
                .stream().map(Object::toString).toList());
    }

    @Test
    void negocioGravaExpectativasExatasDosDoisLadosSemCreditarNoD0() throws Exception {
        negocioPadrao();
        assertEquals(2, numero("SELECT count(*) FROM liquidacao_expectativa WHERE creditada_em IS NULL"));
        assertEquals(0, new BigDecimal("36.00").compareTo(decimal(
                "SELECT valor FROM liquidacao_expectativa WHERE lado='VENDEDOR' AND conta='CONTA-003' AND recurso='CAIXA' AND quantidade IS NULL")));
        assertEquals(4, numero("SELECT quantidade FROM liquidacao_expectativa WHERE lado='COMPRADOR' AND conta='CONTA-002' AND recurso='CUSTODIA' AND valor IS NULL"));
        assertEquals(1, numero("SELECT count(*) FROM liquidacao_expectativa WHERE id='"
                + br.prospero.mo.liquidacao.dominio.CreditoEsperado.id(NEGOCIO, LadoLiquidacao.VENDEDOR) + "' AND evento='" + CAUSA + "'"));
        caixa("CONTA-003", "1000", "1000", "0");
        custodia("CONTA-002", 100, 100);
        caixa("CONTA-002", "964", "904", "60");
        custodia("CONTA-003", 96, 90);
    }

    @Test
    void expectativaIdenticaCreditaUmaVezMesmoComReentregas() throws Exception {
        negocioPadrao();
        assertEquals(Desfecho.CREDITAR, enviar(vendedor()));
        assertEquals(Desfecho.REPETICAO, enviar(vendedor()));
        caixa("CONTA-003", "1036", "1036", "0");
        ObjectNode outraAssinatura = vendedor();
        outraAssinatura.put("ocorridoEm", "2026-10-20T15:00:01Z");
        assertEquals(Desfecho.recusar(MotivoRecusa.REPETICAO_DIVERGENTE), enviar(outraAssinatura));
        assertEquals(Desfecho.CREDITAR, enviar(comprador()));
        assertEquals(Desfecho.REPETICAO, enviar(comprador()));
        caixa("CONTA-003", "1036", "1036", "0");
        custodia("CONTA-002", 104, 104);
        assertEquals(2, numero("SELECT count(*) FROM liquidacao_aplicada"));
        assertEquals(0, numero("SELECT count(*) FROM liquidacao_expectativa WHERE creditada_em IS NULL"));
        assertEquals(List.of("REPETICAO_DIVERGENTE"), motivosRecusa());
    }

    @Test
    void liquidacaoAntesDoNegocioAguardaEEAplicadaNaTransacaoDoNegocio() throws Exception {
        assertEquals(Desfecho.AGUARDAR, enviar(vendedor()));
        assertEquals(Desfecho.REPETICAO, enviar(vendedor()), "reentrega aguardando não ocupa outra vaga");
        assertEquals(1, numero("SELECT count(*) FROM liquidacao_aguardando"));
        caixa("CONTA-003", "1000", "1000", "0");
        negocioPadrao();
        caixa("CONTA-003", "1036", "1036", "0");
        assertEquals(0, numero("SELECT count(*) FROM liquidacao_aguardando"));
        assertEquals(1, numero("SELECT count(*) FROM liquidacao_aplicada"));
        assertEquals(Desfecho.REPETICAO, enviar(vendedor()));
        assertEquals(1, numero("SELECT count(*) FROM liquidacao_expectativa WHERE creditada_em IS NULL"), "comprador segue pendente");
        caixa("CONTA-003", "1036", "1036", "0");
    }

    @Test
    void divergentesAguardandoAntesDoLegitimoNaoImpedemCreditoUnico() throws Exception {
        ObjectNode valorForjado = vendedor();
        ((ObjectNode) valorForjado.get("dados")).put("valor", "99.00");
        ObjectNode contaForjada = evento(NEGOCIO, LadoLiquidacao.VENDEDOR, "CONTA-001", "ALFA3", "36.00", CAUSA);
        assertEquals(Desfecho.AGUARDAR, enviar(valorForjado));
        assertEquals(Desfecho.AGUARDAR, enviar(contaForjada));
        assertEquals(Desfecho.AGUARDAR, enviar(vendedor()));
        negocioPadrao();
        caixa("CONTA-003", "1036", "1036", "0");
        caixa("CONTA-001", "1000", "1000", "0");
        assertEquals(List.of("DIVERGENTE", "DIVERGENTE"), motivosRecusa());
        assertEquals(0, numero("SELECT count(*) FROM liquidacao_aguardando"));
        assertEquals(1, numero("SELECT count(*) FROM liquidacao_aplicada"));
        assertEquals(Desfecho.REPETICAO, enviar(vendedor()));
        assertEquals(Desfecho.recusar(MotivoRecusa.REPETICAO_DIVERGENTE), enviar(valorForjado));
        caixa("CONTA-003", "1036", "1036", "0");
    }

    private ObjectNode semNegocio(int n) {
        return evento("ALFA3-2026-10-18-" + n, LadoLiquidacao.VENDEDOR, "CONTA-003", "ALFA3", "36.00", CAUSA);
    }

    @Test
    void tetoDoAguardandoRecusaNovosSemExpectativaSemTravarOsDemais() throws Exception {
        assertEquals(Desfecho.AGUARDAR, enviar(semNegocio(20)));
        assertEquals(Desfecho.AGUARDAR, enviar(semNegocio(21)));
        assertEquals(Desfecho.AGUARDAR, enviar(semNegocio(22)));
        assertEquals(Desfecho.recusar(MotivoRecusa.AGUARDANDO_ESGOTADO), enviar(semNegocio(23)));
        assertEquals(Desfecho.REPETICAO, enviar(semNegocio(21)), "repetição do aguardando não é recusada no teto");
        assertEquals(3, numero("SELECT count(*) FROM liquidacao_aguardando"));
        negocioPadrao();
        // Com expectativa o teto não se aplica: credita mesmo com o aguardando cheio.
        assertEquals(Desfecho.CREDITAR, enviar(vendedor()));
        assertEquals(3, numero("SELECT count(*) FROM liquidacao_aguardando"));
        assertEquals(List.of("AGUARDANDO_ESGOTADO"), motivosRecusa());
        caixa("CONTA-003", "1036", "1036", "0");
    }

    @Test
    void divergenteComExpectativaERecusadoSemOcuparDeduplicacao() throws Exception {
        negocioPadrao();
        ObjectNode divergente = vendedor();
        ((ObjectNode) divergente.get("dados")).put("valor", "35.99");
        assertEquals(Desfecho.recusar(MotivoRecusa.DIVERGENTE), enviar(divergente));
        caixa("CONTA-003", "1000", "1000", "0");
        assertEquals(0, numero("SELECT count(*) FROM liquidacao_aplicada"));
        assertEquals(Desfecho.CREDITAR, enviar(vendedor()));
        caixa("CONTA-003", "1036", "1036", "0");
    }

    @Test
    void chaveOrigemIdEContratoIncoerentesSaoRecusadosEAPosicaoNaoDuplica() throws Exception {
        negocioPadrao();
        assertEquals(Desfecho.recusar(MotivoRecusa.CHAVE_INCOERENTE), enviar("CONTA-002", bytes(vendedor())));
        ObjectNode origem = vendedor();
        origem.put("origem", "matching-engine");
        assertEquals(Desfecho.recusar(MotivoRecusa.ORIGEM_INVALIDA), enviar(origem));
        ObjectNode id = vendedor();
        id.put("id", UUID.randomUUID().toString());
        assertEquals(Desfecho.recusar(MotivoRecusa.ID_INCOERENTE), enviar(id));
        assertEquals(Desfecho.recusar(MotivoRecusa.CONTRATO_INVALIDO), enviar("CONTA-003", "{".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Desfecho.recusar(MotivoRecusa.CONTRATO_INVALIDO), enviar(null, null));
        // Reentrega da mesma posição (queda entre commit e offset) não duplica a recusa.
        liquidacao.aplicar(new RegistroLiquidacao("CONTA-002", bytes(vendedor()), 0, 0));
        assertEquals(List.of("CHAVE_INCOERENTE", "ORIGEM_INVALIDA", "ID_INCOERENTE", "CONTRATO_INVALIDO", "CONTRATO_INVALIDO"),
                motivosRecusa());
        assertEquals(2, numero("SELECT count(*) FROM liquidacao_recusada WHERE evento IS NULL AND assinatura IS NULL"),
                "JSON ilegível não tem id nem assinatura");
        caixa("CONTA-003", "1000", "1000", "0");
        assertEquals(0, numero("SELECT count(*) FROM liquidacao_aplicada"));
        assertEquals(Desfecho.CREDITAR, enviar(vendedor()));
        caixa("CONTA-003", "1036", "1036", "0");
    }

    @Test
    void consumoFinanceiroAvancaAParticaoComRecusasEAguardandoECreditaUmaVez() throws Exception {
        negocioPadrao();
        var particao = new TopicPartition(ConsumidorLiquidacao.TOPICO, 1);
        var consumidor = new MockConsumer<String, byte[]>("earliest");
        consumidor.assign(List.of(particao));
        consumidor.updateBeginningOffsets(Map.of(particao, 0L));
        ObjectNode divergente = vendedor();
        ((ObjectNode) divergente.get("dados")).put("valor", "1.00");
        List<byte[]> valores = List.of("lixo".getBytes(StandardCharsets.UTF_8), bytes(divergente), bytes(semNegocio(30)),
                bytes(vendedor()), bytes(vendedor()), bytes(comprador()));
        List<String> chaves = List.of("CONTA-003", "CONTA-003", "CONTA-003", "CONTA-003", "CONTA-003", "CONTA-002");
        for (int i = 0; i < valores.size(); i++) {
            consumidor.addRecord(new ConsumerRecord<>(ConsumidorLiquidacao.TOPICO, 1, i, chaves.get(i), valores.get(i)));
        }
        List<String> rejeitados = new ArrayList<>();
        var consumo = ConsumidorLiquidacao.consumo(() -> consumidor, liquidacao, (t, p, o, m) -> rejeitados.add(m),
                new SimpleMeterRegistry());
        CicloConsumo.ciclo(consumo, consumidor);
        assertEquals(6, consumidor.committed(java.util.Set.of(particao)).get(particao).offset());
        assertTrue(rejeitados.isEmpty(), "nenhum descarte: recusas são estados persistidos");
        caixa("CONTA-003", "1036", "1036", "0");
        custodia("CONTA-002", 104, 104);
        assertEquals(List.of("CONTRATO_INVALIDO", "DIVERGENTE"), motivosRecusa());
        assertEquals(1, numero("SELECT count(*) FROM liquidacao_aguardando"));
    }

    @Test
    void creditoConcorrenteComReservaNaoPerdeAtualizacaoNemViolaInvariante() throws Exception {
        int rodadas = 6;
        for (int n = 1; n <= rodadas; n++) {
            Par par = reservas("CONTA-002", "CONTA-003");
            negocio(par, "ALFA3-2026-10-18-" + n, "CONTA-002", "CONTA-003");
        }
        // CONTA-003 vendeu 6 × 36,00 e tem 1000,00 livres; cada rodada credita 36,00 e reserva 100,00 em paralelo.
        var barreira = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int n = 1; n <= rodadas; n++) {
                ObjectNode e = evento("ALFA3-2026-10-18-" + n, LadoLiquidacao.VENDEDOR, "CONTA-003", "ALFA3", "36.00", CAUSA);
                long posicao = 1000 + n;
                Future<Desfecho> credito = executor.submit(() -> {
                    barreira.await(10, TimeUnit.SECONDS);
                    return liquidacao.aplicar(new RegistroLiquidacao("CONTA-003", bytes(e), 2, posicao));
                });
                Future<Boolean> reserva = executor.submit(() -> {
                    barreira.await(10, TimeUnit.SECONDS);
                    return QuarkusTransaction.requiringNew().call(() -> {
                        risco.bloquearConta("CONTA-003");
                        return risco.reservarCaixa("CONTA-003", new BigDecimal("100.00"));
                    });
                });
                assertEquals(Desfecho.CREDITAR, credito.get(30, TimeUnit.SECONDS));
                assertTrue(reserva.get(30, TimeUnit.SECONDS));
            }
        }
        // 1000 + 6 × 36 = 1216 de total; 6 × 100 reservados por esta prova; CHECK disponivel + reservado = total.
        caixa("CONTA-003", "1216", "616", "600");
        assertEquals(0, numero("SELECT count(*) FROM conta_risco WHERE disponivel + reservado <> total"));
    }

    @Test
    void autonegociacaoCreditaOsDoisLadosComEfeitoLiquidoZero() throws Exception {
        Par par = reservas("CONTA-002", "CONTA-002");
        negocio(par, NEGOCIO, "CONTA-002", "CONTA-002");
        caixa("CONTA-002", "964", "904", "60");
        custodia("CONTA-002", 96, 90);
        assertEquals(2, numero("SELECT count(*) FROM liquidacao_expectativa WHERE conta='CONTA-002' AND creditada_em IS NULL"));
        assertEquals(Desfecho.CREDITAR, enviar(evento(NEGOCIO, LadoLiquidacao.VENDEDOR, "CONTA-002", "ALFA3", "36.00", CAUSA)));
        assertEquals(Desfecho.CREDITAR, enviar(comprador()));
        caixa("CONTA-002", "1000", "940", "60");
        custodia("CONTA-002", 100, 94);
        assertEquals(0, numero("SELECT count(*) FROM liquidacao_expectativa WHERE creditada_em IS NULL"));
    }

    private RegraNegocioException prepararComRecusa(String conta) {
        var actor = new CarteiraActor(risco, true, true);
        return assertThrows(RegraNegocioException.class, () -> QuarkusTransaction.requiringNew().run(
                () -> actor.preparar(conta, new PreparacaoCarteiraRequestDTO("5000.00", Map.of()))));
    }

    @Test
    void preparacaoComCreditoPendenteE409AntesDeQualquerEscritaEReservaVivaDepoisDoCredito() throws Exception {
        negocioPadrao();
        var recusa = prepararComRecusa("CONTA-003");
        assertEquals("LIQUIDACAO_PENDENTE", recusa.codigo());
        assertEquals(br.prospero.mo.comum.dominio.Categoria.CONFLITO, recusa.categoria());
        caixa("CONTA-003", "1000", "1000", "0");
        custodia("CONTA-003", 96, 90);
        enviar(vendedor());
        // Pendência resolvida: a recusa passa a ser a reserva viva já existente na conta (RF04b).
        assertEquals("RESERVA_VIVA", prepararComRecusa("CONTA-003").codigo());
        assertEquals("LIQUIDACAO_PENDENTE", prepararComRecusa("CONTA-002").codigo());
        var desligada = new CarteiraActor(risco, true, false);
        var semFlag = assertThrows(RegraNegocioException.class, () -> QuarkusTransaction.requiringNew().run(
                () -> desligada.preparar("CONTA-002", new PreparacaoCarteiraRequestDTO("5000.00", Map.of()))));
        assertEquals("RESERVA_VIVA", semFlag.codigo(), "flag desligada mantém o comportamento anterior");
        caixa("CONTA-003", "1036", "1036", "0");
    }

    @Test
    void migracaoV7EAditivaSobreCarteirasEDeduplicacaoExistentes() throws Exception {
        String proprio = "liquidacao_v7_" + UUID.randomUUID().toString().replace("-", "");
        var configuracao = org.flywaydb.core.Flyway.configure().dataSource(System.getenv("MATCHING_TEST_DB_URL"),
                        System.getenv("MATCHING_TEST_DB_USER"), System.getenv("MATCHING_TEST_DB_PASSWORD"))
                .schemas(proprio).defaultSchema(proprio).locations("classpath:db/migration");
        try {
            configuracao.target("6").load().migrate();
            try (var c = conexao(); var s = c.createStatement()) {
                c.setSchema(proprio);
                s.execute("UPDATE conta_risco SET total=964, disponivel=964 WHERE conta='CONTA-002'");
                s.execute("INSERT INTO matching_negocio(negocio, assinatura) VALUES('" + NEGOCIO + "','" + "a".repeat(64) + "')");
            }
            configuracao.target("latest").load().migrate();
            try (var c = conexao(); var s = c.createStatement()) {
                c.setSchema(proprio);
                try (var r = s.executeQuery("SELECT total FROM conta_risco WHERE conta='CONTA-002'")) {
                    assertTrue(r.next());
                    assertEquals(0, new BigDecimal("964").compareTo(r.getBigDecimal(1)));
                }
                try (var r = s.executeQuery("SELECT (SELECT count(*) FROM liquidacao_expectativa) + (SELECT count(*) FROM matching_negocio)")) {
                    assertTrue(r.next());
                    assertEquals(1, r.getInt(1), "sem backfill de expectativas para negócios anteriores");
                }
            }
        } finally {
            try (var c = conexao(); var s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + proprio + " CASCADE");
            }
        }
    }
}
