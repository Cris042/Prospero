package br.prospero.fo.atualizacoes;

import br.prospero.fo.atualizacoes.jpa.RepositorioRevisoesJpa;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(ExpiracaoAtualizacoesTest.BancoBloqueado.class)
class ExpiracaoAtualizacoesTest {
    @TestHTTPResource URI base;

    public static class BancoBloqueado implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("prospero.atualizacoes.consulta-habilitada", "true");
        }
        @Override public Set<Class<?>> getEnabledAlternatives() { return Set.of(FonteBloqueada.class); }
    }

    @Alternative
    @ApplicationScoped
    public static class FonteBloqueada extends RepositorioRevisoesJpa {
        static final CountDownLatch ENTROU = new CountDownLatch(1);
        static final CountDownLatch LIBERAR = new CountDownLatch(1);
        public FonteBloqueada() { super(null); }
        @Inject public FonteBloqueada(Instance<EntityManager> gerenciadores) { super(gerenciadores); }
        @Override public Map<String, Long> consultar() {
            ENTROU.countDown();
            try {
                if (!LIBERAR.await(12, TimeUnit.SECONDS)) throw new IllegalStateException("prazo QA banco bloqueado");
            } catch (InterruptedException erro) { Thread.currentThread().interrupt(); throw new IllegalStateException(erro); }
            return Map.of("CONTA-002", 1L);
        }
    }

    @Test
    void jwtExpiradoFechaCanalEnquantoConsultaDeRevisaoContinuaBloqueada() throws Exception {
        String token = Jwt.issuer("prospero-local").audience("prospero-front-office").subject("CONTA-002")
                .groups(Set.of("ordens:leitura")).expiresIn(3)
                .signWithSecret("chave-sintetica-de-teste-com-mais-de-32-bytes");
        var fechamento = new CompletableFuture<Integer>();
        var socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", "Bearer " + token).header("Origin", "http://" + base.getRawAuthority())
                .buildAsync(URI.create("ws://" + base.getRawAuthority() + "/api/v1/atualizacoes"), new WebSocket.Listener() {
                    @Override public void onOpen(WebSocket ws) { ws.request(1); }
                    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence texto, boolean ultima) {
                        ws.request(1); return null;
                    }
                    @Override public CompletionStage<?> onClose(WebSocket ws, int codigo, String motivo) {
                        fechamento.complete(codigo); return null;
                    }
                    @Override public void onError(WebSocket ws, Throwable erro) { fechamento.completeExceptionally(erro); }
                }).get(5, TimeUnit.SECONDS);
        try {
            assertTrue(FonteBloqueada.ENTROU.await(2, TimeUnit.SECONDS), "poll deve entrar no repositório falso");
            assertEquals(1008, fechamento.get(5, TimeUnit.SECONDS), "expiração não depende da resposta do banco");
            assertEquals(1, FonteBloqueada.LIBERAR.getCount(), "consulta permanece bloqueada ao fechar o socket");
        } finally {
            FonteBloqueada.LIBERAR.countDown();
            socket.abort();
        }
    }
}
