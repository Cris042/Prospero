package br.prospero.me.matching;

import br.prospero.me.matching.helper.EnvelopeMatching;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Caracterização do parser após a extração da regra de combinação para Admissao: aceite/rejeição em toda a matriz
 * tipo × validade × lado × preços, comparado com as expressões que EnvelopeMatching.comando/par usavam antes.
 */
class EnvelopeCombinacaoTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] PRECOS = {null, "9.00", "10.00", "11.00"};
    private static final String[] TIPOS = {"MARKET", "LIMIT", "STOP", "STOP_LIMIT"};
    private static final String[] VALIDADES = {"DIA", "IOC", "FOK"};
    private static final String[] LADOS = {"COMPRA", "VENDA"};

    @Test
    void admissaoSimplesV1eV2() throws Exception {
        int casos = 0;
        for (String tipo : TIPOS) {
            boolean stop = tipo.startsWith("STOP");
            for (String validade : VALIDADES) {
                for (String lado : LADOS) {
                    for (String limite : PRECOS) {
                        for (String maximo : PRECOS) {
                            for (String gatilho : stop ? PRECOS : new String[]{null}) {
                                byte[] corpo = simples(stop ? 2 : 1, tipo, validade, lado, limite, maximo, gatilho);
                                boolean esperado = !simplesInvalidoOriginal(tipo, validade, lado, limite, maximo, gatilho);
                                assertEquals(esperado, aceito(corpo),
                                        String.join("/", tipo, validade, lado, "" + limite, "" + maximo, "" + gatilho));
                                casos++;
                            }
                        }
                    }
                }
            }
        }
        assertEquals(2 * 3 * 2 * 16 + 2 * 3 * 2 * 64, casos);
    }

    @Test
    void parOcoV3() throws Exception {
        int casos = 0;
        for (String lado : LADOS) {
            for (String tipoStop : new String[]{"STOP", "STOP_LIMIT"}) {
                for (String alvo : PRECOS) {
                    for (String limiteStop : PRECOS) {
                        for (String gatilho : PRECOS) {
                            for (String maximo : PRECOS) {
                                byte[] corpo = par(lado, tipoStop, alvo, limiteStop, gatilho, maximo);
                                boolean esperado = !parInvalidoOriginal(lado, tipoStop, alvo, limiteStop, gatilho, maximo);
                                assertEquals(esperado, aceito(corpo),
                                        String.join("/", lado, tipoStop, "" + alvo, "" + limiteStop, "" + gatilho, "" + maximo));
                                casos++;
                            }
                        }
                    }
                }
            }
        }
        assertEquals(2 * 2 * 256, casos);
    }

    /**
     * Contrato × tipo: v1 só transporta MARKET/LIMIT e v2 só STOP/STOP_LIMIT; tipo desconhecido é inválido em ambos.
     * Antes da refatoração, EnvelopeMatching.comando rejeitava com a mesma guarda (versao == 1 ? MARKET/LIMIT : stop).
     */
    @Test
    void versaoIncompativelComTipoEhInvalida() throws Exception {
        assertTrue(aceito(simples(1, "LIMIT", "DIA", "VENDA", "10.00", null, null)));
        assertTrue(aceito(simples(1, "MARKET", "IOC", "VENDA", null, null, null)));
        assertTrue(aceito(simples(2, "STOP", "DIA", "VENDA", null, null, "10.00")));
        assertTrue(aceito(simples(2, "STOP_LIMIT", "DIA", "VENDA", "10.00", null, "10.00")));
        assertFalse(aceito(simples(1, "STOP", "DIA", "VENDA", null, null, null)), "v1 STOP");
        assertFalse(aceito(simples(1, "STOP_LIMIT", "DIA", "VENDA", "10.00", null, null)), "v1 STOP_LIMIT");
        assertFalse(aceito(simples(2, "MARKET", "IOC", "VENDA", null, null, "10.00")), "v2 MARKET");
        assertFalse(aceito(simples(2, "LIMIT", "DIA", "VENDA", "10.00", null, "10.00")), "v2 LIMIT");
        assertFalse(aceito(simples(1, "ICEBERG", "DIA", "VENDA", "10.00", null, null)), "v1 desconhecido");
        assertFalse(aceito(simples(2, "ICEBERG", "DIA", "VENDA", "10.00", null, "10.00")), "v2 desconhecido");
    }

    /** Cópia das guardas de EnvelopeMatching.comando antes da refatoração. */
    private static boolean simplesInvalidoOriginal(String tipo, String validade, String lado, String limiteTexto,
                                                   String maximoTexto, String gatilhoTexto) {
        BigDecimal limite = preco(limiteTexto), maximo = preco(maximoTexto), gatilho = preco(gatilhoTexto);
        boolean stop = tipo.equals("STOP") || tipo.equals("STOP_LIMIT");
        boolean compra = lado.equals("COMPRA"), limit = tipo.equals("LIMIT") || tipo.equals("STOP_LIMIT");
        return limit != (limite != null) || compra != (maximo != null)
                || (stop && (gatilho == null || !validade.equals("DIA")))
                || (!stop && !limit && validade.equals("DIA"))
                || (tipo.equals("STOP_LIMIT") && (compra ? limite.compareTo(gatilho) < 0 : limite.compareTo(gatilho) > 0))
                || (compra && limit && limite.compareTo(maximo) > 0);
    }

    /** Cópia das guardas de EnvelopeMatching.par antes da refatoração (pernas com papéis, tipos e ordens válidos). */
    private static boolean parInvalidoOriginal(String lado, String tipoStop, String alvoTexto, String limiteTexto,
                                               String gatilhoTexto, String maximoTexto) {
        BigDecimal alvo = preco(alvoTexto), limiteStop = preco(limiteTexto), gatilho = preco(gatilhoTexto);
        BigDecimal maximo = preco(maximoTexto);
        boolean compra = lado.equals("COMPRA");
        if (compra != (maximo != null)) {
            return true;
        }
        return alvo == null || gatilho == null
                || tipoStop.equals("STOP_LIMIT") != (limiteStop != null)
                || (compra ? alvo.compareTo(gatilho) >= 0 : alvo.compareTo(gatilho) <= 0)
                || (limiteStop != null && (compra ? limiteStop.compareTo(gatilho) < 0 : limiteStop.compareTo(gatilho) > 0))
                || (compra && (alvo.compareTo(maximo) > 0 || limiteStop != null && limiteStop.compareTo(maximo) > 0));
    }

    private static boolean aceito(byte[] corpo) {
        try {
            EnvelopeMatching.comando(corpo, "ALFA3");
            return true;
        } catch (IllegalArgumentException erro) {
            return false;
        }
    }

    private static BigDecimal preco(String texto) {
        return texto == null ? null : new BigDecimal(texto);
    }

    private static ObjectNode raiz(int versao) {
        UUID intencao = UUID.randomUUID();
        ObjectNode raiz = JSON.createObjectNode();
        raiz.put("id", UUID.nameUUIDFromBytes(("admissao:" + intencao).getBytes(StandardCharsets.UTF_8)).toString());
        raiz.put("tipo", "ORDEM_ADMITIDA");
        raiz.put("versao", versao);
        raiz.put("origem", "middle-office");
        raiz.put("correlacao", intencao.toString());
        raiz.put("ocorridoEm", "2026-10-18T12:00:00Z");
        raiz.put("conta", "CONTA-002");
        raiz.putNull("traceparent");
        return raiz;
    }

    private static byte[] simples(int versao, String tipo, String validade, String lado, String limite, String maximo,
                                  String gatilho) throws Exception {
        ObjectNode raiz = raiz(versao);
        ObjectNode dados = raiz.putObject("dados");
        dados.put("ordem", UUID.randomUUID().toString());
        dados.put("ticker", "ALFA3");
        dados.put("lado", lado);
        dados.put("tipo", tipo);
        dados.put("validade", validade);
        dados.put("quantidade", 100);
        dados.put("precoLimite", limite);
        dados.put("precoMaximo", maximo);
        if (versao == 2) {
            dados.put("precoGatilho", gatilho);
        }
        dados.put("dataSessao", "2026-10-18");
        return JSON.writeValueAsBytes(raiz);
    }

    private static byte[] par(String lado, String tipoStop, String alvo, String limiteStop, String gatilho,
                              String maximo) throws Exception {
        ObjectNode raiz = raiz(3);
        ObjectNode dados = raiz.putObject("dados");
        dados.put("ticker", "ALFA3");
        dados.put("lado", lado);
        dados.put("dataSessao", "2026-10-18");
        dados.put("precoMaximo", maximo);
        ArrayNode pernas = dados.putArray("pernas");
        ObjectNode takeProfit = pernas.addObject();
        takeProfit.put("papel", "TAKE_PROFIT");
        takeProfit.put("ordem", UUID.randomUUID().toString());
        takeProfit.put("tipo", "LIMIT");
        takeProfit.put("validade", "DIA");
        takeProfit.put("quantidade", 100);
        takeProfit.put("precoLimite", alvo);
        ObjectNode stopLoss = pernas.addObject();
        stopLoss.put("papel", "STOP_LOSS");
        stopLoss.put("ordem", UUID.randomUUID().toString());
        stopLoss.put("tipo", tipoStop);
        stopLoss.put("validade", "DIA");
        stopLoss.put("quantidade", 100);
        stopLoss.put("precoLimite", limiteStop);
        stopLoss.put("precoGatilho", gatilho);
        return JSON.writeValueAsBytes(raiz);
    }
}
