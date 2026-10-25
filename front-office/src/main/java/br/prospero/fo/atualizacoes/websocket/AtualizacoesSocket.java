package br.prospero.fo.atualizacoes.websocket;

import br.prospero.fo.atualizacoes.actor.PublicarAtualizacoesActor;
import io.quarkus.websockets.next.OnBinaryMessage;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import io.vertx.core.buffer.Buffer;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;

@WebSocket(path = "/api/v1/atualizacoes", endpointId = "atualizacoes")
@RolesAllowed("ordens:leitura")
public class AtualizacoesSocket {
    private final PublicarAtualizacoesActor atualizacoes;
    @Inject
    public AtualizacoesSocket(PublicarAtualizacoesActor atualizacoes) { this.atualizacoes = atualizacoes; }

    @OnOpen
    public void abrir(WebSocketConnection conexao) { atualizacoes.abrir(conexao); }

    @OnClose
    public void fechar(WebSocketConnection conexao) { atualizacoes.fechar(conexao); }

    @OnTextMessage
    public Uni<Void> texto(String mensagem, WebSocketConnection conexao) { return conexao.close(new CloseReason(1008)); }

    @OnBinaryMessage
    public Uni<Void> binario(Buffer mensagem, WebSocketConnection conexao) { return conexao.close(new CloseReason(1008)); }
}
