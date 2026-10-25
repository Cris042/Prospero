package br.prospero.fo.atualizacoes.actor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PublicarAtualizacoesActorTest {
    @Test
    void clienteLentoMantemSomenteUmEnvioEFechaAposPrazoSemFila() throws Exception {
        var metricas = new SimpleMeterRegistry();
        var actor = new PublicarAtualizacoesActor(null, metricas, false);
        var envios = new AtomicInteger();
        var aberta = new AtomicBoolean(true);
        var fechamento = new CompletableFuture<Integer>();
        var dados = new Dados();
        WebSocketConnection conexao = (WebSocketConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WebSocketConnection.class}, (proxy, metodo, argumentos) -> switch (metodo.getName()) {
                    case "userData" -> dados;
                    case "isOpen" -> aberta.get();
                    case "sendText" -> { envios.incrementAndGet(); yield Uni.createFrom().nothing(); }
                    case "close" -> {
                        aberta.set(false);
                        fechamento.complete(((CloseReason) argumentos[0]).getCode());
                        yield Uni.createFrom().voidItem();
                    }
                    default -> throw new UnsupportedOperationException(metodo.getName());
                });
        actor.iniciar(null);
        try {
            var acesso = actor.reservar("CONTA-002", Instant.now().getEpochSecond() + 30);
            assertNotNull(acesso);
            dados.put(PublicarAtualizacoesActor.ACESSO, acesso);
            actor.abrir(conexao);
            assertEquals(1, envios.get());
            assertEquals(1013, fechamento.get(5, TimeUnit.SECONDS));
            assertEquals(1, envios.get(), "ticks enquanto envio está pendente não criam fila ou envio concorrente");
            assertEquals(0, metricas.get("prospero.atualizacoes.conexoes").gauge().value());
        } finally { actor.parar(null); metricas.close(); }
    }

    private static final class Dados implements UserData {
        private final Map<TypedKey<?>, Object> valores = new ConcurrentHashMap<>();
        @SuppressWarnings("unchecked") public <T> T get(TypedKey<T> chave) { return (T) valores.get(chave); }
        @SuppressWarnings("unchecked") public <T> T put(TypedKey<T> chave, T valor) { return (T) valores.put(chave, valor); }
        @SuppressWarnings("unchecked") public <T> T remove(TypedKey<T> chave) { return (T) valores.remove(chave); }
        public int size() { return valores.size(); }
        public void clear() { valores.clear(); }
    }
}
