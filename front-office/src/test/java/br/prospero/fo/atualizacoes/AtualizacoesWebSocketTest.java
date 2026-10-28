package br.prospero.fo.atualizacoes;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AtualizacoesWebSocketTest {
    @TestHTTPResource URI base;
    private static final String CHAVE = "chave-sintetica-de-teste-com-mais-de-32-bytes";
    private static final ObjectMapper JSON = new ObjectMapper();

    private String token(String conta, String audiencia, long segundos, String... escopos) {
        return Jwt.issuer("prospero-local").audience(audiencia).subject(conta).groups(Set.of(escopos))
                .expiresIn(segundos).signWithSecret(CHAVE);
    }

    private String token(String conta) { return token(conta, "prospero-front-office", 60, "ordens:leitura"); }
    private URI url(String sufixo) { return URI.create("ws://" + base.getRawAuthority() + "/api/v1/atualizacoes" + sufixo); }
    private String origem() { return "http://" + base.getRawAuthority(); }

    private WebSocket.Builder builder(String credencial, String origem) {
        var b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3));
        if (credencial != null) b.header("Authorization", "Bearer " + credencial);
        if (origem != null) b.header("Origin", origem);
        return b;
    }

    private int recusar(WebSocket.Builder builder, String sufixo) {
        var erro = assertThrows(CompletionException.class, () -> builder.buildAsync(url(sufixo), new Escuta()).join());
        var handshake = assertInstanceOf(WebSocketHandshakeException.class, erro.getCause());
        return handshake.getResponse().statusCode();
    }

    @Test
    void abreComAvisoInicialMinimoSemDadosFinanceirosEFechaMensagemDoCliente() throws Exception {
        var escuta = new Escuta();
        var socket = builder(token("CONTA-002"), origem()).buildAsync(url(""), escuta).get(5, TimeUnit.SECONDS);
        try {
            String texto = escuta.textos.poll(5, TimeUnit.SECONDS);
            assertNotNull(texto);
            var frame = JSON.readTree(texto);
            var nomes = new java.util.HashSet<String>();
            frame.fieldNames().forEachRemaining(nomes::add);
            assertEquals(Set.of("versao", "tipo", "revisao"), nomes);
            assertEquals(1, frame.path("versao").asInt());
            assertEquals("RESSINCRONIZAR", frame.path("tipo").asText());
            assertTrue(frame.path("revisao").isTextual());
            assertEquals("0", frame.path("revisao").asText());
            socket.sendText("cliente-nao-pode-escrever", true).get(3, TimeUnit.SECONDS);
            assertEquals(1008, escuta.fechamento.get(5, TimeUnit.SECONDS));
        } finally { socket.abort(); }
    }

    @Test
    void handshakeExigeJwtAudienciaEscopoEConta() {
        assertEquals(401, recusar(builder(null, origem()), ""));
        assertEquals(401, recusar(builder(token("CONTA-002", "prospero-back-office", 60, "ordens:leitura"), origem()), ""));
        assertEquals(403, recusar(builder(token("CONTA-002", "prospero-front-office", 60, "ordens:enviar"), origem()), ""));
        assertEquals(403, recusar(builder(token("CONTA-999"), origem()), ""));
    }

    @Test
    void handshakeRecusaAContaDeMedicao005() {
        // V01: a CONTA-005 fica fora do WebSocket (só 001 a 004); com JWT válido, a recusa vem da lista de contas.
        assertEquals(403, recusar(builder(token("CONTA-005"), origem()), ""));
    }

    @Test
    void handshakeRecusaOriginAusenteExternoQueryESubprotocolo() {
        assertEquals(403, recusar(builder(token("CONTA-002"), null), ""));
        assertEquals(403, recusar(builder(token("CONTA-002"), "http://evil.example"), ""));
        assertEquals(403, recusar(builder(token("CONTA-002"), origem()), "?token=nao-permitido"));
        assertEquals(403, recusar(builder(token("CONTA-002"), origem()).subprotocols("credencial-nao-permitida"), ""));
    }

    @Test
    void expirarJwtFechaSocketAbertoComCodigoDePolitica() throws Exception {
        var escuta = new Escuta();
        var socket = builder(token("CONTA-003", "prospero-front-office", 2, "ordens:leitura"), origem())
                .buildAsync(url(""), escuta).get(5, TimeUnit.SECONDS);
        try { assertEquals(1008, escuta.fechamento.get(8, TimeUnit.SECONDS)); }
        finally { socket.abort(); }
    }

    @Test
    void nonaConexaoDaContaERecusadaSemRemoverAsOitoAnteriores() throws Exception {
        var sockets = new ArrayList<WebSocket>();
        try {
            for (int i = 0; i < 8; i++) {
                var escuta = new Escuta();
                sockets.add(builder(token("CONTA-004"), origem()).buildAsync(url(""), escuta).get(5, TimeUnit.SECONDS));
                assertNotNull(escuta.textos.poll(5, TimeUnit.SECONDS));
            }
            assertEquals(429, recusar(builder(token("CONTA-004"), origem()), ""));
            assertTrue(sockets.stream().noneMatch(WebSocket::isOutputClosed));
        } finally { sockets.forEach(WebSocket::abort); }
    }

    private static final class Escuta implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> textos = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> fechamento = new CompletableFuture<>();
        private final StringBuilder mensagem = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence dados, boolean ultima) {
            mensagem.append(dados);
            if (ultima) { textos.add(mensagem.toString()); mensagem.setLength(0); }
            socket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int codigo, String motivo) {
            fechamento.complete(codigo); return null;
        }
        @Override public void onError(WebSocket socket, Throwable erro) { fechamento.completeExceptionally(erro); }
    }
}
