package br.prospero.fo.cotacao.helper;

import br.prospero.fo.cotacao.dominio.LadoCotado;
import br.prospero.fo.cotacao.dominio.LivroCotado;
import br.prospero.fo.cotacao.dominio.NivelCotado;
import br.prospero.fo.cotacao.dominio.SessaoCotacao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Snapshot de leitura em JSON com decimais em texto (sem perda de escala) e versão ordenável por texto. */
public final class SerializadorCotacao {
    private static final ObjectMapper JSON = new ObjectMapper();

    private SerializadorCotacao() {
    }

    /** Dia da sessão (8 dígitos) + revisão (19 dígitos): a comparação lexicográfica no Redis segue a ordem numérica. */
    public static String versao(SessaoCotacao sessao) {
        if (sessao.revisao() < 0) throw new IllegalArgumentException("Revisão negativa.");
        return String.format("%08d%019d", sessao.sessao().toEpochDay(), sessao.revisao());
    }

    /** Versão do "sem cotação": menor que a de qualquer sessão real. */
    public static final String VERSAO_AUSENTE = "0".repeat(27);
    public static final String AUSENTE = "{\"ausente\":true}";

    public static boolean ausente(String texto) {
        return AUSENTE.equals(texto);
    }

    public static String serializar(LivroCotado livro) {
        var s = livro.sessao();
        ObjectNode raiz = JSON.createObjectNode();
        raiz.put("ticker", s.ticker()).put("sessao", s.sessao().toString()).put("ultimo", texto(s.ultimoPreco()))
                .put("volumeQuantidade", s.volumeQuantidade()).put("volumeFinanceiro", s.volumeFinanceiro().toPlainString())
                .put("revisao", s.revisao()).put("atualizadoEm", s.atualizadoEm().toString()).put("encerrada", s.encerrada());
        raiz.set("compras", lado(livro.compras()));
        raiz.set("vendas", lado(livro.vendas()));
        return raiz.toString();
    }

    public static LivroCotado ler(String texto) {
        try {
            JsonNode r = JSON.readTree(texto);
            var sessao = new SessaoCotacao(r.get("ticker").asText(), LocalDate.parse(r.get("sessao").asText()),
                    decimal(r.get("ultimo")), r.get("volumeQuantidade").asLong(), new BigDecimal(r.get("volumeFinanceiro").asText()),
                    r.get("revisao").asLong(), Instant.parse(r.get("atualizadoEm").asText()), r.get("encerrada").asBoolean());
            return new LivroCotado(sessao, lado(r.get("compras")), lado(r.get("vendas")));
        } catch (Exception erro) {
            throw new IllegalArgumentException("Snapshot de cotação inválido.", erro);
        }
    }

    private static ObjectNode lado(LadoCotado lado) {
        ObjectNode no = JSON.createObjectNode().put("niveisTotal", lado.niveisTotal()).put("quantidadeTotal", lado.quantidadeTotal());
        ArrayNode niveis = no.putArray("niveis");
        for (NivelCotado n : lado.niveis()) {
            niveis.addObject().put("preco", n.preco().toPlainString()).put("quantidade", n.quantidade())
                    .put("ofertas", n.ofertas()).put("acumulada", n.quantidadeAcumulada());
        }
        return no;
    }

    private static LadoCotado lado(JsonNode no) {
        List<NivelCotado> niveis = new ArrayList<>();
        for (JsonNode n : no.get("niveis")) {
            niveis.add(new NivelCotado(new BigDecimal(n.get("preco").asText()), n.get("quantidade").asLong(),
                    n.get("ofertas").asInt(), n.get("acumulada").asLong()));
        }
        return new LadoCotado(niveis, no.get("niveisTotal").asInt(), no.get("quantidadeTotal").asLong());
    }

    private static String texto(BigDecimal valor) { return valor == null ? null : valor.toPlainString(); }

    private static BigDecimal decimal(JsonNode no) { return no == null || no.isNull() ? null : new BigDecimal(no.asText()); }
}
