package br.prospero.mo.liquidacao;

import br.prospero.mo.liquidacao.dominio.ContratoRecusado;
import br.prospero.mo.liquidacao.dominio.CreditoEsperado;
import br.prospero.mo.liquidacao.dominio.LadoLiquidacao;
import br.prospero.mo.liquidacao.dominio.MotivoRecusa;
import br.prospero.mo.liquidacao.helper.EnvelopeLiquidacao;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

import static br.prospero.mo.liquidacao.EventosSinteticos.bytes;
import static br.prospero.mo.liquidacao.EventosSinteticos.evento;
import static org.junit.jupiter.api.Assertions.*;

/** RF08c: contrato LIQUIDACAO_EFETIVADA v1 limitado e estrito; recusa com motivo enumerado, sem o payload. */
class EnvelopeLiquidacaoTest {
    private static final String NEGOCIO = "ALFA3-2026-10-18-1";
    private static final UUID CAUSA = UUID.fromString("0000abcd-0000-3000-8000-00000000000f");

    private static ObjectNode vendedor() {
        return evento(NEGOCIO, LadoLiquidacao.VENDEDOR, "CONTA-003", "ALFA3", "36.00", CAUSA);
    }

    @Test
    void eventoValidoProduzCreditoIdDerivadoEAssinaturaEstavel() {
        var lido = EnvelopeLiquidacao.ler(bytes(vendedor()));
        assertEquals(CreditoEsperado.id(NEGOCIO, LadoLiquidacao.VENDEDOR), lido.id());
        assertEquals(CAUSA, lido.causacao());
        assertEquals(new CreditoEsperado(NEGOCIO, LadoLiquidacao.VENDEDOR, "CONTA-003", "ALFA3", new BigDecimal("36.00")), lido.credito());
        assertTrue(lido.assinatura().matches("[0-9a-f]{64}"));
        // Assinatura canônica: a ordem dos campos não muda o hash; qualquer valor muda.
        String reordenado = "{\"dados\":" + vendedor().get("dados") + ",\"versao\":1,\"tipo\":\"LIQUIDACAO_EFETIVADA\",\"id\":\""
                + lido.id() + "\",\"origem\":\"back-office\",\"causacao\":\"" + CAUSA + "\",\"ocorridoEm\":\"2026-10-20T15:00:00Z\",\"traceparent\":null}";
        assertEquals(lido.assinatura(), EnvelopeLiquidacao.ler(reordenado.getBytes(StandardCharsets.UTF_8)).assinatura());
        ObjectNode outro = vendedor();
        outro.put("ocorridoEm", "2026-10-20T15:00:01Z");
        assertNotEquals(lido.assinatura(), EnvelopeLiquidacao.ler(bytes(outro)).assinatura());
        var comprador = EnvelopeLiquidacao.ler(bytes(evento(NEGOCIO, LadoLiquidacao.COMPRADOR, "CONTA-002", "ALFA3", "4", CAUSA)));
        assertEquals(new BigDecimal("4"), comprador.credito().valor());
    }

    private static void recusado(MotivoRecusa motivo, Consumer<ObjectNode> adulterar) {
        ObjectNode e = vendedor();
        adulterar.accept(e);
        var erro = assertThrows(ContratoRecusado.class, () -> EnvelopeLiquidacao.ler(bytes(e)));
        assertEquals(motivo, erro.motivo());
        assertEquals(motivo.name(), erro.getMessage(), "mensagem sem conteúdo do evento");
    }

    private static ObjectNode dados(ObjectNode e) {
        return (ObjectNode) e.get("dados");
    }

    @Test
    void origemEIdIncoerentesTemMotivoProprio() {
        recusado(MotivoRecusa.ORIGEM_INVALIDA, e -> e.put("origem", "matching-engine"));
        recusado(MotivoRecusa.ID_INCOERENTE, e -> e.put("id", UUID.randomUUID().toString()));
        recusado(MotivoRecusa.ID_INCOERENTE, e -> e.put("id", CreditoEsperado.id(NEGOCIO, LadoLiquidacao.COMPRADOR).toString()));
        var erro = assertThrows(ContratoRecusado.class, () -> {
            ObjectNode e = vendedor();
            e.put("id", UUID.randomUUID().toString());
            EnvelopeLiquidacao.ler(bytes(e));
        });
        assertNotNull(erro.id(), "id legível vai para a recusa");
        assertNotNull(erro.assinatura());
    }

    @Test
    void camposTiposEFormatosForaDoContratoSaoContratoInvalido() {
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> e.put("tipo", "LIQUIDACAO_FALHOU"));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> e.put("versao", 2));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> e.put("versao", "1"));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> e.put("extra", 1));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> e.remove("causacao"));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> e.put("causacao", "nao-uuid"));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> e.put("causacao", CAUSA.toString().toUpperCase()));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> e.put("ocorridoEm", "ontem"));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> e.put("traceparent", "x"));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> dados(e).put("extra", 1));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> dados(e).put("lado", "COMPRA"));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> dados(e).put("recurso", "CUSTODIA"));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> dados(e).put("conta", "CONTA-999"));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> dados(e).put("ticker", "ZETA3"));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> dados(e).put("ticker", "BETA3"));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> dados(e).put("negocio", "ALFA3-2026-10-18-0"));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> dados(e).put("valor", 36));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> dados(e).put("valor", "36"));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> dados(e).put("valor", "36.001"));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> dados(e).put("valor", "0.00"));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> dados(e).put("valor", "-1.00"));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> dados(e).put("valor", "1e3"));
        recusado(MotivoRecusa.CONTRATO_INVALIDO, e -> dados(e).put("dataLiquidacao", "2026-02-30"));
    }

    @Test
    void custodiaExigeQuantidadeInteiraPositivaLimitada() {
        for (String valor : new String[]{"0", "04", "1.00", "1000001", "-3"}) {
            ObjectNode e = evento(NEGOCIO, LadoLiquidacao.COMPRADOR, "CONTA-002", "ALFA3", valor, CAUSA);
            assertEquals(MotivoRecusa.CONTRATO_INVALIDO,
                    assertThrows(ContratoRecusado.class, () -> EnvelopeLiquidacao.ler(bytes(e))).motivo(), valor);
        }
        assertDoesNotThrow(() -> EnvelopeLiquidacao.ler(bytes(evento(NEGOCIO, LadoLiquidacao.COMPRADOR, "CONTA-002", "ALFA3", "1000000", CAUSA))));
    }

    @Test
    void envelopeLimitadoEJsonEstrito() {
        assertEquals(MotivoRecusa.CONTRATO_INVALIDO, assertThrows(ContratoRecusado.class, () -> EnvelopeLiquidacao.ler(null)).motivo());
        assertEquals(MotivoRecusa.CONTRATO_INVALIDO, assertThrows(ContratoRecusado.class,
                () -> EnvelopeLiquidacao.ler(new byte[EnvelopeLiquidacao.TAMANHO_MAXIMO + 1])).motivo());
        byte[] cauda = (vendedor() + " {}").getBytes(StandardCharsets.UTF_8);
        assertEquals(MotivoRecusa.CONTRATO_INVALIDO, assertThrows(ContratoRecusado.class, () -> EnvelopeLiquidacao.ler(cauda)).motivo());
        String duplicado = vendedor().toString().replaceFirst("\\{", "{\"origem\":\"back-office\",");
        assertEquals(MotivoRecusa.CONTRATO_INVALIDO, assertThrows(ContratoRecusado.class,
                () -> EnvelopeLiquidacao.ler(duplicado.getBytes(StandardCharsets.UTF_8))).motivo());
        assertEquals(MotivoRecusa.CONTRATO_INVALIDO, assertThrows(ContratoRecusado.class,
                () -> EnvelopeLiquidacao.ler("[]".getBytes(StandardCharsets.UTF_8))).motivo());
    }
}
