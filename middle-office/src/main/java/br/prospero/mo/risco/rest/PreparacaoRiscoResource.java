package br.prospero.mo.risco.rest;

import br.prospero.mo.comum.dto.ProblemaDTO;
import br.prospero.mo.comum.rest.Problemas;
import br.prospero.mo.risco.actor.CarteiraActor;
import br.prospero.mo.risco.dto.CarteiraResponseDTO;
import br.prospero.mo.risco.dto.PreparacaoCarteiraRequestDTO;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/v1/preparacoes-risco/contas/{conta}")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("risco:preparacao")
@SecurityRequirement(name = "jwt")
@Tag(name = "Preparação de risco", description = "Carteiras sintéticas de teste (CONTA-002/003/005); desabilitada fora do "
        + "ambiente local.")
public class PreparacaoRiscoResource {
    private final CarteiraActor actor;

    @Inject
    public PreparacaoRiscoResource(CarteiraActor actor) {
        this.actor = actor;
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Redefinir caixa e custódia de uma conta de teste, apagando suas reservas")
    @APIResponse(responseCode = "200", description = "Carteira redefinida atomicamente.",
            content = @Content(schema = @Schema(implementation = CarteiraResponseDTO.class)))
    @APIResponse(responseCode = "400", description = "JSON, saldo ou custódia inválidos.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "403", description = "Token sem escopo risco:preparacao ou conta não preparável.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "404", description = "Preparação desabilitada.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "422", description = "Ativo desconhecido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    public Response preparar(@PathParam("conta") String conta, @NotNull @Valid PreparacaoCarteiraRequestDTO pedido) {
        return Response.ok(actor.preparar(conta, pedido)).header("Cache-Control", "no-store").build();
    }
}
