package br.prospero.mo.sessao;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

/** RF05c.4b: encerramento manual por API do MO com escopo próprio; um comando por ativo/data na outbox. */
@QuarkusTest
class EncerramentoSessaoResourceTest {
    private static final String CHAVE = "chave-sintetica-de-teste-com-mais-de-32-bytes";
    @Inject RepositorioSessaoMemoria repositorio;

    private static String token(String... grupos) {
        return Jwt.issuer("prospero-local").audience("prospero-middle-office").groups(Set.of(grupos)).expiresIn(300)
                .signWithSecret(CHAVE);
    }

    private static io.restassured.response.Response encerrar(String token, String ticker, String data) {
        var pedido = given();
        if (token != null) pedido = pedido.auth().oauth2(token);
        return pedido.when().post("/api/v1/sessoes/" + ticker + "/" + data + "/encerramento");
    }

    @Test
    void registraUmComandoDeterministicoERepeticaoEInocua() throws Exception {
        String tok = token("sessao:encerrar");
        UUID id = UUID.nameUUIDFromBytes("encerramento:GAMA3:2026-10-18".getBytes(StandardCharsets.UTF_8));
        encerrar(tok, "GAMA3", "2026-10-18").then().statusCode(202).header("Cache-Control", "no-store")
                .body("ticker", equalTo("GAMA3")).body("dataSessao", equalTo("2026-10-18"))
                .body("comando", equalTo(id.toString())).body("repetido", equalTo(false));
        encerrar(tok, "GAMA3", "2026-10-18").then().statusCode(202).body("repetido", equalTo(true));
        var comandos = repositorio.comandos().stream().filter(c -> c.id().equals(id)).toList();
        assertEquals(1, comandos.size());
        var m = comandos.getFirst();
        assertEquals("matching.comandos.v1", m.topico());
        assertEquals("GAMA3", m.chave());
        assertEquals("ENCERRAR_SESSAO", m.tipo());
        var json = new ObjectMapper().readTree(m.payload());
        assertEquals(Set.of("id", "tipo", "versao", "origem", "correlacao", "ocorridoEm", "traceparent", "dados"),
                Set.copyOf(iterable(json.fieldNames())));
        assertEquals(id.toString(), json.get("id").textValue());
        assertEquals(id.toString(), json.get("correlacao").textValue());
        assertEquals(1, json.get("versao").intValue());
        assertEquals("middle-office", json.get("origem").textValue());
        assertEquals("GAMA3", json.get("dados").get("ticker").textValue());
        assertEquals("2026-10-18", json.get("dados").get("dataSessao").textValue());
    }

    @Test
    void autorizacaoEEntradas() {
        encerrar(null, "GAMA3", "2026-10-18").then().statusCode(401);
        encerrar(token("risco:preparacao"), "GAMA3", "2026-10-18").then().statusCode(403);
        String tok = token("sessao:encerrar");
        encerrar(tok, "ZZZZ3", "2026-10-18").then().statusCode(404).body("codigo", equalTo("ATIVO_DESCONHECIDO"));
        encerrar(tok, "GAMA3", "2026-13-40").then().statusCode(400).body("codigo", equalTo("DATA_INVALIDA"));
        encerrar(tok, "GAMA3", "18-10-2026").then().statusCode(400).body("codigo", equalTo("DATA_INVALIDA"));
    }

    private static <T> java.util.List<T> iterable(java.util.Iterator<T> it) {
        java.util.List<T> lista = new java.util.ArrayList<>();
        it.forEachRemaining(lista::add);
        return lista;
    }
}
