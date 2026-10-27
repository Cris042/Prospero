package br.prospero.fo.atualizacoes.websocket;

import br.prospero.fo.atualizacoes.actor.PublicarAtualizacoesActor;
import br.prospero.fo.atualizacoes.helper.OrigemAtualizacoes;
import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

@ApplicationScoped
public class VerificarUpgrade implements HttpUpgradeCheck {
    private final PublicarAtualizacoesActor atualizacoes;
    @Inject
    public VerificarUpgrade(PublicarAtualizacoesActor atualizacoes) { this.atualizacoes = atualizacoes; }

    @Override
    public boolean appliesTo(String endpointId) { return "atualizacoes".equals(endpointId); }

    @Override
    public Uni<CheckResult> perform(HttpUpgradeContext contexto) {
        var pedido = contexto.httpRequest();
        if (pedido.headers().getAll("Origin").size() != 1 || pedido.headers().getAll("Host").size() != 1
                || !OrigemAtualizacoes.permitida(pedido.getHeader("Origin"), pedido.getHeader("Host"))
                || pedido.query() != null || pedido.getHeader("Sec-WebSocket-Protocol") != null) {
            return CheckResult.rejectUpgrade(403);
        }
        return contexto.securityIdentity().flatMap(identidade -> {
            if (identidade == null || identidade.isAnonymous() || !(identidade.getPrincipal() instanceof JsonWebToken token)) {
                return CheckResult.rejectUpgrade(401);
            }
            if (!identidade.hasRole("ordens:leitura") || token.getSubject() == null
                    || !token.getSubject().matches("CONTA-00[1-4]")) return CheckResult.rejectUpgrade(403);
            if (token.getExpirationTime() <= java.time.Instant.now().getEpochSecond()) return CheckResult.rejectUpgrade(401);
            var acesso = atualizacoes.reservar(token.getSubject(), token.getExpirationTime());
            if (acesso == null) return CheckResult.rejectUpgrade(429);
            contexto.userData().put(PublicarAtualizacoesActor.ACESSO, acesso);
            return CheckResult.permitUpgrade();
        });
    }
}
