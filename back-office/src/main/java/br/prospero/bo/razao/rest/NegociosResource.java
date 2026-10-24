package br.prospero.bo.razao.rest;

import br.prospero.bo.posicoes.rest.ProblemasPosicoes;
import br.prospero.bo.razao.actor.ConsultarRazaoActor;
import br.prospero.bo.razao.dto.NegocioDetalheDTO;
import br.prospero.bo.razao.dto.PaginaNegociosDTO;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/v1/negocios")
@Produces("application/json")
@RolesAllowed(RazaoResource.ESCOPO)
@SecurityRequirement(name = "jwt")
@Tag(name = "Negócios", description = "Histórico de negócios da conta do token desde a origem do log (ADR-016), sem contraparte.")
public class NegociosResource {
    private final ConsultarRazaoActor actor;
    private final JsonWebToken token;
    @Inject public NegociosResource(ConsultarRazaoActor actor, JsonWebToken token) { this.actor = actor; this.token = token; }

    @GET
    @Operation(summary = "Listar negócios da própria conta",
            description = "Mais recentes primeiro; um item por lado. Liquidação PENDENTE até o D+2 (RF08d).")
    @APIResponse(responseCode = "200", description = "Página de negócios.",
            content = @Content(schema = @Schema(implementation = PaginaNegociosDTO.class)))
    @APIResponse(responseCode = "400", description = "Cursor ou limite inválido, inclusive cursor de outra conta.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemasPosicoes.ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente, inválido ou expirado.")
    @APIResponse(responseCode = "403", description = "Escopo ou conta não permitidos.")
    @APIResponse(responseCode = "503", description = "Projeção em bootstrap, atrasada ou em quarentena técnica.")
    public Response listar(@Parameter(description = "Cursor opaco retornado em proximoCursor.") @QueryParam("cursor") String cursor,
                           @Parameter(description = "1 a 100; padrão 50.") @QueryParam("limite") String limite) {
        return Response.ok(actor.negocios(token.getSubject(), cursor, limite)).header("Cache-Control", "no-store").build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Consultar negócio da própria conta", description = "Inexistente e sem participação respondem o mesmo 404.")
    @APIResponse(responseCode = "200", description = "Negócio com os lados da conta.",
            content = @Content(schema = @Schema(implementation = NegocioDetalheDTO.class)))
    @APIResponse(responseCode = "404", description = "Inexistente ou sem participação da conta.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemasPosicoes.ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente, inválido ou expirado.")
    @APIResponse(responseCode = "403", description = "Escopo ou conta não permitidos.")
    @APIResponse(responseCode = "503", description = "Projeção em bootstrap, atrasada ou em quarentena técnica.")
    public Response buscar(@PathParam("id") String id) {
        return Response.ok(actor.negocio(token.getSubject(), id)).header("Cache-Control", "no-store").build();
    }
}
