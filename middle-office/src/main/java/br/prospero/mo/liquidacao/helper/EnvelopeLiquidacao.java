package br.prospero.mo.liquidacao.helper;

import br.prospero.mo.liquidacao.dominio.ContratoRecusado;
import br.prospero.mo.liquidacao.dominio.CreditoEsperado;
import br.prospero.mo.liquidacao.dominio.EventoLiquidacao;
import br.prospero.mo.liquidacao.dominio.LadoLiquidacao;
import br.prospero.mo.liquidacao.dominio.MotivoRecusa;
import br.prospero.mo.liquidacao.dominio.RecursoCredito;
import br.prospero.mo.matching.helper.EnvelopeMatching;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Contrato LIQUIDACAO_EFETIVADA v1 de {@code liquidacao.eventos.v1} (ADR-016), um evento por lado:
 * <pre>
 * {"id": derivado de negocio e lado, "tipo": "LIQUIDACAO_EFETIVADA", "versao": 1, "origem": "back-office",
 *  "causacao": id do evento NEGOCIO, "ocorridoEm": instante ISO-8601, "traceparent": null ou W3C,
 *  "dados": {"negocio", "lado": COMPRADOR|VENDEDOR, "conta", "recurso": CUSTODIA|CAIXA, "ticker",
 *            "valor": quantidade inteira (custódia) ou reais com duas casas (caixa), "dataLiquidacao": AAAA-MM-DD}}
 * </pre>
 * Valores são textuais; a data de liquidação é do BO e só tem o formato conferido. Erros carregam apenas o motivo.
 */
public final class EnvelopeLiquidacao {
    public static final int TAMANHO_MAXIMO = 4096;
    public static final String TIPO = "LIQUIDACAO_EFETIVADA";
    public static final String ORIGEM = "back-office";
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> ENVELOPE = Set.of("id", "tipo", "versao", "origem", "causacao", "ocorridoEm",
            "traceparent", "dados");
    private static final Set<String> DADOS = Set.of("negocio", "lado", "conta", "recurso", "ticker", "valor",
            "dataLiquidacao");
    private static final Set<String> CONTAS = Set.of("CONTA-001", "CONTA-002", "CONTA-003", "CONTA-004", "CONTA-005");
    private static final Set<String> ATIVOS = Set.of("ALFA3", "BETA3", "GAMA3", "DELTA3", "OMEGA3");
    private static final Pattern NEGOCIO = Pattern.compile("[A-Z0-9]{1,8}-\\d{4}-\\d{2}-\\d{2}-[1-9][0-9]{0,17}");
    private static final Pattern CAIXA = Pattern.compile("(0|[1-9][0-9]{0,15})\\.[0-9]{2}");
    private static final Pattern QUANTIDADE = Pattern.compile("[1-9][0-9]{0,6}");
    private static final Pattern TRACEPARENT = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    private EnvelopeLiquidacao() {
    }

    /** Lê e valida; fora do contrato lança {@link ContratoRecusado} com motivo, id e assinatura quando legíveis. */
    public static EventoLiquidacao ler(byte[] bytes) {
        JsonNode raiz = arvore(bytes);
        String assinatura = EnvelopeMatching.assinatura(raiz);
        UUID id = idLegivel(raiz);
        try {
            return validar(raiz, assinatura, id);
        } catch (ContratoRecusado recusa) {
            throw recusa;
        } catch (RuntimeException erro) {
            throw new ContratoRecusado(MotivoRecusa.CONTRATO_INVALIDO, id, assinatura);
        }
    }

    private static JsonNode arvore(byte[] bytes) {
        if (bytes == null || bytes.length > TAMANHO_MAXIMO) {
            throw new ContratoRecusado(MotivoRecusa.CONTRATO_INVALIDO, null, null);
        }
        try {
            JsonNode raiz = JSON.readTree(bytes);
            exigir(raiz != null && raiz.isObject());
            return raiz;
        } catch (Exception erro) {
            throw new ContratoRecusado(MotivoRecusa.CONTRATO_INVALIDO, null, null);
        }
    }

    private static UUID idLegivel(JsonNode raiz) {
        try {
            return uuid(raiz, "id");
        } catch (RuntimeException erro) {
            return null;
        }
    }

    private static EventoLiquidacao validar(JsonNode r, String assinatura, UUID id) {
        campos(r, ENVELOPE);
        exigir(id != null);
        exigir(texto(r, "tipo").equals(TIPO));
        exigir(r.get("versao").isInt() && r.get("versao").intValue() == 1);
        String origem = texto(r, "origem");
        UUID causacao = uuid(r, "causacao");
        Instant.parse(texto(r, "ocorridoEm"));
        exigir(r.get("traceparent").isNull() || TRACEPARENT.matcher(texto(r, "traceparent")).matches());
        CreditoEsperado credito = credito(r.get("dados"));
        if (!origem.equals(ORIGEM)) {
            throw new ContratoRecusado(MotivoRecusa.ORIGEM_INVALIDA, id, assinatura);
        }
        if (!id.equals(credito.id())) {
            throw new ContratoRecusado(MotivoRecusa.ID_INCOERENTE, id, assinatura);
        }
        return new EventoLiquidacao(id, assinatura, causacao, credito);
    }

    private static CreditoEsperado credito(JsonNode d) {
        campos(d, DADOS);
        String negocio = texto(d, "negocio");
        String ticker = texto(d, "ticker");
        exigir(NEGOCIO.matcher(negocio).matches() && ATIVOS.contains(ticker) && negocio.startsWith(ticker + "-"));
        LadoLiquidacao lado = LadoLiquidacao.valueOf(texto(d, "lado"));
        exigir(RecursoCredito.valueOf(texto(d, "recurso")) == lado.recurso());
        String conta = texto(d, "conta");
        exigir(CONTAS.contains(conta));
        String valor = texto(d, "valor");
        exigir((lado.recurso() == RecursoCredito.CAIXA ? CAIXA : QUANTIDADE).matcher(valor).matches());
        String data = texto(d, "dataLiquidacao");
        exigir(LocalDate.parse(data).toString().equals(data));
        return new CreditoEsperado(negocio, lado, conta, ticker, new BigDecimal(valor));
    }

    private static void campos(JsonNode no, Set<String> nomes) {
        exigir(no != null && no.isObject() && no.size() == nomes.size());
        no.fieldNames().forEachRemaining(c -> exigir(nomes.contains(c)));
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode valor = no.get(campo);
        exigir(valor != null && valor.isTextual());
        return valor.textValue();
    }

    private static UUID uuid(JsonNode no, String campo) {
        String texto = texto(no, campo);
        UUID valor = UUID.fromString(texto);
        exigir(valor.toString().equals(texto));
        return valor;
    }

    private static void exigir(boolean condicao) {
        if (!condicao) {
            throw new IllegalArgumentException("contrato");
        }
    }
}
