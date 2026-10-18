package br.prospero.mo.risco.rest;

import br.prospero.mo.comum.dto.ProblemaDTO;
import br.prospero.mo.comum.rest.Problemas;
import br.prospero.mo.risco.actor.CarteiraActor;
import br.prospero.mo.risco.dto.CarteiraResponseDTO;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/v1/risco/carteira")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("risco:leitura")
@SecurityRequirement(name = "jwt")
@Tag(name = "Risco", description = "Recursos de risco da conta do token (RF04).")
public class CarteiraResource {
    private final CarteiraActor actor;
    private final JsonWebToken token;

    @Inject
    public CarteiraResource(CarteiraActor actor, JsonWebToken token) {
        this.actor = actor;
        this.token = token;
    }

    @GET
    @Operation(summary = "Consultar caixa e custódia, disponíveis e reservados, da conta do token")
    @APIResponse(responseCode = "200", description = "Carteira de risco da conta (claim sub).",
            content = @Content(schema = @Schema(implementation = CarteiraResponseDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "403", description = "Token sem escopo risco:leitura ou sem conta cadastrada.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    public Response consultar() {
        return Response.ok(actor.consultar(token.getSubject())).header("Cache-Control", "no-store").build();
    }
}
