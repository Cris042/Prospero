package br.prospero.bo.posicoes.rest;

import br.prospero.bo.posicoes.actor.ConsultarPosicoesActor;
import br.prospero.bo.posicoes.dto.PosicoesResponseDTO;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/v1/posicoes")
@Produces("application/json")
@RolesAllowed("posicoes:leitura")
@SecurityRequirement(name = "jwt")
@Tag(name = "Posições", description = "Posição/PnL dos negócios desde a origem do log, distinta da custódia inicial.")
public class PosicoesResource {
    private final ConsultarPosicoesActor actor;
    private final JsonWebToken token;
    @Inject public PosicoesResource(ConsultarPosicoesActor actor, JsonWebToken token) { this.actor = actor; this.token = token; }

    @GET
    @Operation(summary = "Consultar posições dos cinco ativos da conta do token",
            description = "Valores textuais, quatro casas HALF_EVEN. Bootstrap/lag/quarentena retornam 503; não representam posição zero.")
    @APIResponse(responseCode = "200", description = "Snapshot dos negócios da conta autenticada.",
            content = @Content(schema = @Schema(implementation = PosicoesResponseDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente, inválido ou expirado.")
    @APIResponse(responseCode = "403", description = "Escopo ou conta não permitidos.")
    @APIResponse(responseCode = "503", description = "Projeção indisponível ou em reconstrução.")
    public Response consultar() {
        return Response.ok(actor.consultar(token.getSubject())).header("Cache-Control", "no-store").build();
    }
}
