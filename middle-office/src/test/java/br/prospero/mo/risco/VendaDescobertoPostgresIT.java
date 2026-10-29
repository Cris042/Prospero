package br.prospero.mo.risco;

import br.prospero.mo.cancelamento.CancelamentoBanco;
import br.prospero.mo.liquidacao.dominio.CreditoEsperado;
import br.prospero.mo.liquidacao.dominio.LadoLiquidacao;
import br.prospero.mo.liquidacao.jpa.RepositorioLiquidacaoJpa;
import br.prospero.mo.matching.actor.AplicarMatchingActor;
import br.prospero.mo.matching.helper.EnvelopeMatching;
import br.prospero.mo.risco.actor.AvaliarRiscoActor;
import br.prospero.mo.risco.dominio.ComandoRisco;
import br.prospero.mo.risco.dominio.Lado;
import br.prospero.mo.risco.dominio.MotivoRejeicao;
import br.prospero.mo.risco.dominio.Perna;
import br.prospero.mo.risco.dominio.ResultadoRisco;
import br.prospero.mo.risco.dominio.TipoOrdem;
import br.prospero.mo.risco.dominio.Validade;
import br.prospero.mo.risco.helper.EnvelopeAdmissao;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-021 em PostgreSQL real, pelo caminho de produção: risco reserva a parte a descoberto, o consumidor de matching
 * executa e encerra, e o crédito D+2 da recompra libera garantia e exposição em duas etapas. Opt-in.
 */
@QuarkusTest
@TestProfile(CancelamentoBanco.class)
class VendaDescobertoPostgresIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate SESSAO = LocalDate.of(2026, 10, 18);

    @AfterAll
    static void removerSchemaProprio() throws Exception {
        CancelamentoBanco.removerSchemaProprio();
    }

    @Inject AvaliarRiscoActor risco;
    @Inject AplicarMatchingActor matching;
    @Inject RepositorioLiquidacaoJpa liquidacao;
    @Inject EntityManager em;

    private static ComandoRisco ordem(String conta, Lado lado, long quantidade, Perna perna) {
        return new ComandoRisco(UUID.randomUUID(), UUID.randomUUID(), conta, "ALFA3", lado, new BigDecimal("10.00"),
                List.of(perna), null, SESSAO);
    }

    private static Perna limitIoc(long quantidade) {
        return new Perna(UUID.randomUUID(), TipoOrdem.LIMIT, quantidade, new BigDecimal("10.00"), null, Validade.IOC);
    }

    private Object[] linha(String sql) {
        return QuarkusTransaction.requiringNew().call(() -> (Object[]) em.createNativeQuery(sql).getSingleResult());
    }

    /** reservado, exposição; custódia ALFA3: total, disponível, reservado, descoberto reservado/executado, garantia. */
    private void estado(String reservado, String exposicao, long total, long disponivel, long custodiaReservada,
                        long descobertoReservado, long descobertoExecutado, String garantia) {
        Object[] c = linha("SELECT reservado, exposicao_vendida FROM conta_risco WHERE conta='CONTA-002'");
        assertEquals(0, new BigDecimal(reservado).compareTo((BigDecimal) c[0]), "caixa reservado");
        assertEquals(0, new BigDecimal(exposicao).compareTo((BigDecimal) c[1]), "exposição vendida");
        Object[] k = linha("""
                SELECT total, disponivel, reservado, descoberto_reservado, descoberto_executado, garantia_executada
                FROM custodia_risco WHERE conta='CONTA-002' AND ticker='ALFA3'""");
        assertEquals(List.of(total, disponivel, custodiaReservada, descobertoReservado, descobertoExecutado),
                List.of(((Number) k[0]).longValue(), ((Number) k[1]).longValue(), ((Number) k[2]).longValue(),
                        ((Number) k[3]).longValue(), ((Number) k[4]).longValue()), "custódia");
        assertEquals(0, new BigDecimal(garantia).compareTo((BigDecimal) k[5]), "garantia executada");
    }

    private ObjectNode envelope(UUID intencao, String tipo) {
        ObjectNode e = JSON.createObjectNode();
        e.put("id", UUID.randomUUID().toString()); e.put("tipo", tipo); e.put("versao", 1);
        e.put("origem", "matching-engine"); e.put("correlacao", EnvelopeAdmissao.id(intencao).toString());
        e.put("ocorridoEm", "2026-10-18T12:00:00Z"); e.put("ticker", "ALFA3");
        e.put("dataSessao", SESSAO.toString()); e.putNull("traceparent");
        return e;
    }

    private void aplicar(ObjectNode e) throws Exception {
        matching.aplicar(EnvelopeMatching.ler(JSON.writeValueAsBytes(e)));
    }

    @Test
    void vendaParcialmenteDescobertaReservaExecutaEncerraERecompraLiberandoGarantia() throws Exception {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("UPDATE conta_risco SET total=1000,disponivel=1000,reservado=0,exposicao_vendida=0,limite_vendido=200000")
                    .executeUpdate();
            em.createNativeQuery("""
                    UPDATE custodia_risco SET total=5,disponivel=5,reservado=0,compra_aberta=0,descoberto_reservado=0,
                    descoberto_executado=0,garantia_executada=0,limite_posicao=NULL WHERE conta='CONTA-002' AND ticker='ALFA3'""")
                    .executeUpdate();
        });

        // Vende 8 com 5 em custódia: 3 a descoberto, garantia 3 × ⌊10,00 × 1,10⌋ = 33,00, exposição 3 × 10,00.
        Perna pernaVenda = limitIoc(8);
        ComandoRisco venda = ordem("CONTA-002", Lado.VENDA, 8, pernaVenda);
        var decisao = risco.avaliar(venda, null).decisao();
        assertEquals(ResultadoRisco.RESERVADA, decisao.resultado());
        estado("33.00", "30.00", 5, 0, 5, 3, 0, "0");
        Object[] gravado = linha("SELECT quantidade_descoberta, garantia FROM decisao_risco WHERE intencao='" + venda.intencao() + "'");
        assertEquals(3L, ((Number) gravado[0]).longValue());
        assertEquals(0, new BigDecimal("33.00").compareTo((BigDecimal) gravado[1]));

        // Recusas sem efeito: exposição acima do limite e lado vendido acima do limite de posição.
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE conta_risco SET limite_vendido=50 WHERE conta='CONTA-002'").executeUpdate());
        assertEquals(MotivoRejeicao.EXPOSICAO_VENDIDA_EXCEDIDA,
                risco.avaliar(ordem("CONTA-002", Lado.VENDA, 3, limitIoc(3)), null).decisao().motivo(), "30 + 30 > 50");
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("UPDATE conta_risco SET limite_vendido=200000 WHERE conta='CONTA-002'").executeUpdate();
            em.createNativeQuery("UPDATE custodia_risco SET limite_posicao=4 WHERE conta='CONTA-002' AND ticker='ALFA3'").executeUpdate();
        });
        assertEquals(MotivoRejeicao.LIMITE_POSICAO_EXCEDIDO,
                risco.avaliar(ordem("CONTA-002", Lado.VENDA, 2, limitIoc(2)), null).decisao().motivo(), "3 + 2 > 4 vendidas");
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE custodia_risco SET limite_posicao=NULL WHERE conta='CONTA-002' AND ticker='ALFA3'").executeUpdate());
        estado("33.00", "30.00", 5, 0, 5, 3, 0, "0");

        // CONTA-003 compra 7: consome as 5 cobertas e 2 a descoberto; o saldo de 1 é cancelado pelo IOC.
        Perna pernaCompra = limitIoc(7);
        ComandoRisco compra = ordem("CONTA-003", Lado.COMPRA, 7, pernaCompra);
        assertEquals(ResultadoRisco.RESERVADA, risco.avaliar(compra, null).decisao().resultado());
        ObjectNode negocio = envelope(compra.intencao(), "NEGOCIO"), d = negocio.putObject("dados");
        d.put("negocio", "ALFA3-2026-10-18-1"); d.put("preco", "10.00"); d.put("quantidade", 7);
        d.put("ordemCompra", pernaCompra.id().toString()); d.put("intencaoCompra", compra.intencao().toString());
        d.put("contaCompra", "CONTA-003"); d.put("ordemVenda", pernaVenda.id().toString());
        d.put("intencaoVenda", venda.intencao().toString()); d.put("contaVenda", "CONTA-002"); d.put("agressor", "COMPRA");
        aplicar(negocio);
        aplicar(negocio);
        estado("33.00", "30.00", 0, 0, 0, 1, 2, "22.00");

        ObjectNode terminal = envelope(venda.intencao(), "ORDEM_ENCERRADA"), t = terminal.putObject("dados");
        t.put("ordem", pernaVenda.id().toString()); t.put("intencao", venda.intencao().toString()); t.put("conta", "CONTA-002");
        t.put("motivo", "SALDO_CANCELADO_IOC"); t.put("executada", 7); t.put("cancelada", 1);
        aplicar(terminal);
        aplicar(terminal);
        estado("22.00", "20.00", 0, 0, 0, 0, 2, "22.00");

        // Recompra em duas liquidações D+2 de 1: cada uma cobre 1, libera metade da garantia e 10,00 de exposição.
        recomprar("REC-1");
        estado("11.00", "10.00", 0, 0, 0, 0, 1, "11.00");
        recomprar("REC-2");
        estado("0.00", "0.00", 0, 0, 0, 0, 0, "0");
        Object[] caixa = linha("SELECT total, disponivel FROM conta_risco WHERE conta='CONTA-002'");
        assertEquals(0, new BigDecimal("1000.00").compareTo((BigDecimal) caixa[0]), "a garantia nunca saiu do caixa");
        assertEquals(0, new BigDecimal("1000.00").compareTo((BigDecimal) caixa[1]));
    }

    private void recomprar(String negocio) {
        var credito = new CreditoEsperado(negocio, LadoLiquidacao.COMPRADOR, "CONTA-002", "ALFA3", BigDecimal.ONE);
        QuarkusTransaction.requiringNew().run(() -> {
            liquidacao.registrarExpectativas(UUID.randomUUID(), List.of(credito));
            liquidacao.creditar(credito.id(), "assinatura-" + negocio, credito);
        });
    }
}
