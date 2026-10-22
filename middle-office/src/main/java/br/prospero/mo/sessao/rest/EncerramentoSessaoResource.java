package br.prospero.mo.sessao.rest;

import br.prospero.mo.comum.dto.ProblemaDTO;
import br.prospero.mo.comum.rest.Problemas;
import br.prospero.mo.sessao.actor.EncerrarSessaoActor;
import br.prospero.mo.sessao.dto.EncerramentoResponseDTO;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
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

@Path("/api/v1/sessoes/{ticker}/{data}/encerramento")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("sessao:encerrar")
@SecurityRequirement(name = "jwt")
@Tag(name = "Sessão", description = "Encerramento manual da sessão de um ativo (RF05c.4); desabilitado fora do ambiente local.")
public class EncerramentoSessaoResource {
    private final EncerrarSessaoActor actor;

    @Inject
    public EncerramentoSessaoResource(EncerrarSessaoActor actor) {
        this.actor = actor;
    }

    @POST
    @Operation(summary = "Encerrar a sessão de um ativo: expira ofertas DIA, stops pendentes e pernas OCO vivas")
    @APIResponse(responseCode = "202", description = "Comando registrado (ou já registrado); a expiração ocorre no ME.",
            content = @Content(schema = @Schema(implementation = EncerramentoResponseDTO.class)))
    @APIResponse(responseCode = "400", description = "Data inválida.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "403", description = "Token sem escopo sessao:encerrar.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "404", description = "Ativo desconhecido ou encerramento desabilitado.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    public Response encerrar(@PathParam("ticker") String ticker, @PathParam("data") String data) {
        // Sem traceparent externo: o ME valida o formato estritamente e o comando não deve depender do cliente.
        return Response.accepted(actor.encerrar(ticker, data, null)).header("Cache-Control", "no-store").build();
    }
}
