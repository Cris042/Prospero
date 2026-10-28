package br.prospero.mo.cancelamento.helper;

import br.prospero.mo.cancelamento.dominio.EstadoCancelamento;
import br.prospero.mo.cancelamento.dominio.OperacaoCancelamento;
import br.prospero.mo.cancelamento.dominio.SolicitacaoCancelamento;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Contrato determinístico do que o MO escreve no cancelamento (ADR-013 RF10c2): CANCELAR_ORDEM para o matching e
 * RESULTADO_CANCELAMENTO para o risco. Ids derivados da ordem tornam a reentrega idempotente na outbox.
 */
public final class EnvelopeCancelamento {
    private static final ObjectMapper JSON = new ObjectMapper();

    private EnvelopeCancelamento() {
    }

    private static UUID derivado(String semente) {
        return UUID.nameUUIDFromBytes(semente.getBytes(StandardCharsets.UTF_8));
    }

    /** Id do CANCELAR_ORDEM e causação raiz dos eventos v5 do ME. */
    public static UUID comando(UUID ordem) {
        return derivado("cancelamento:" + ordem);
    }

    public static UUID resultadoId(UUID ordem) {
        return derivado("resultado-cancelamento:" + ordem);
    }

    /** Identidade do pedido, sem traceparent: a mesma ordem com alvo diferente é inconsistência, não repetição. */
    public static String assinatura(SolicitacaoCancelamento pedido) {
        String identidade = String.join("|", pedido.id().toString(), pedido.ordem().toString(), pedido.intencao().toString(),
                pedido.conta(), pedido.ticker(), pedido.dataSessao().toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identidade.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException erro) {
            throw new IllegalStateException(erro);
        }
    }

    public static String cancelarOrdem(OperacaoCancelamento operacao, Instant agora) {
        ObjectNode raiz = JSON.createObjectNode();
        raiz.put("id", operacao.comando().toString());
        raiz.put("tipo", "CANCELAR_ORDEM");
        raiz.put("versao", 1);
        raiz.put("origem", "middle-office");
        raiz.put("correlacao", operacao.admissao().toString());
        raiz.put("ocorridoEm", agora.toString());
        raiz.put("traceparent", operacao.traceparent());
        ObjectNode dados = raiz.putObject("dados");
        dados.put("ordem", operacao.ordem().toString());
        dados.put("intencao", operacao.intencao().toString());
        dados.put("conta", operacao.conta());
        dados.put("ticker", operacao.ticker());
        dados.put("dataSessao", operacao.dataSessao().toString());
        return raiz.toString();
    }

    /** RESULTADO_CANCELAMENTO de uma operação já decidida: CONFIRMADO sem motivo ou RECUSADO com o motivo fechado. */
    public static String resultado(OperacaoCancelamento operacao, Instant agora) {
        boolean confirmado = operacao.estado() == EstadoCancelamento.CONFIRMADO;
        if (!confirmado && operacao.estado() != EstadoCancelamento.RECUSADO) {
            throw new IllegalStateException("Resultado de cancelamento exige operação decidida.");
        }
        ObjectNode raiz = JSON.createObjectNode();
        raiz.put("id", resultadoId(operacao.ordem()).toString());
        raiz.put("tipo", "RESULTADO_CANCELAMENTO");
        raiz.put("versao", 1);
        raiz.put("origem", "middle-office");
        raiz.put("correlacao", operacao.admissao().toString());
        raiz.put("ocorridoEm", agora.toString());
        raiz.put("conta", operacao.conta());
        raiz.put("traceparent", operacao.traceparent());
        ObjectNode dados = raiz.putObject("dados");
        dados.put("ordem", operacao.ordem().toString());
        dados.put("resultado", confirmado ? "CONFIRMADO" : "RECUSADO");
        dados.put("motivo", confirmado ? null : operacao.motivo().name());
        return raiz.toString();
    }
}
