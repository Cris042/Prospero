package br.prospero.bo.razao.rest;

import br.prospero.bo.posicoes.rest.ProblemasPosicoes;
import br.prospero.bo.razao.actor.ConsultarRazaoActor;
import br.prospero.bo.razao.dto.PaginaRazaoDTO;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
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

@Path("/api/v1/razao")
@Produces("application/json")
@RolesAllowed(RazaoResource.ESCOPO)
@SecurityRequirement(name = "jwt")
@Tag(name = "Razão", description = "Razão em partidas dobradas da conta do token (ADR-016); contraparte Câmara simulada.")
public class RazaoResource {
    /** Escopo de leitura próprio da RF08b, distinto de posicoes:leitura. */
    public static final String ESCOPO = "razao:leitura";
    private final ConsultarRazaoActor actor;
    private final JsonWebToken token;
    @Inject public RazaoResource(ConsultarRazaoActor actor, JsonWebToken token) { this.actor = actor; this.token = token; }

    @GET
    @Operation(summary = "Listar lançamentos da própria conta",
            description = "Mais recentes primeiro. Débitos = créditos por transação e unidade; valores textuais.")
    @APIResponse(responseCode = "200", description = "Página do razão.",
            content = @Content(schema = @Schema(implementation = PaginaRazaoDTO.class)))
    @APIResponse(responseCode = "400", description = "Cursor ou limite inválido, inclusive cursor de outra conta.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemasPosicoes.ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente, inválido ou expirado.")
    @APIResponse(responseCode = "403", description = "Escopo ou conta não permitidos.")
    @APIResponse(responseCode = "503", description = "Projeção em bootstrap, atrasada ou em quarentena técnica.")
    public Response listar(@Parameter(description = "Cursor opaco retornado em proximoCursor.") @QueryParam("cursor") String cursor,
                           @Parameter(description = "1 a 100; padrão 50.") @QueryParam("limite") String limite) {
        return Response.ok(actor.razao(token.getSubject(), cursor, limite)).header("Cache-Control", "no-store").build();
    }
}
