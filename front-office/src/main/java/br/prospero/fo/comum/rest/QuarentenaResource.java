package br.prospero.fo.comum.rest;

import br.prospero.fo.comum.dto.ProblemaDTO;
import br.prospero.fo.comum.rest.Problemas;
import br.prospero.fo.comum.actor.ReprocessarQuarentenaActor;
import br.prospero.fo.comum.dto.QuarentenaItemDTO;
import br.prospero.fo.comum.dto.ResolucaoQuarentenaDTO;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/v1/quarentena")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("quarentena:reprocessar")
@SecurityRequirement(name = "jwt")
@Tag(name = "Quarentena", description = "Mensagens Kafka retiradas por erro permanente (ADR-019); acesso operacional.")
public class QuarentenaResource {
    private final ReprocessarQuarentenaActor actor;
    private final io.quarkus.security.identity.SecurityIdentity identidade;

    @Inject
    public QuarentenaResource(ReprocessarQuarentenaActor actor, io.quarkus.security.identity.SecurityIdentity identidade) {
        this.actor = actor;
        this.identidade = identidade;
    }

    /** Autor registrado no log de auditoria; tokens sem sub aparecem como "sem-sub". */
    private String autor() {
        var principal = identidade.getPrincipal();
        return principal == null || principal.getName() == null || principal.getName().isBlank() ? "sem-sub" : principal.getName();
    }

    @GET
    @Operation(summary = "Listar mensagens pendentes, da mais antiga para a mais nova, sem payload")
    @APIResponse(responseCode = "200", description = "Pendentes.",
            content = @Content(schema = @Schema(implementation = QuarentenaItemDTO.class, type = org.eclipse.microprofile.openapi.annotations.enums.SchemaType.ARRAY)))
    @APIResponse(responseCode = "400", description = "Limite fora de 1..200.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "403", description = "Token sem escopo quarentena:reprocessar.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    public Response pendentes(@QueryParam("limite") @DefaultValue("50") int limite) {
        return Response.ok(actor.pendentes(limite)).header("Cache-Control", "no-store").build();
    }

    @POST
    @Path("/{id}/reprocessamento")
    @Operation(summary = "Reaplicar a mensagem pelo caminho do consumidor; repetição é inócua")
    @APIResponse(responseCode = "200", description = "Reprocessada agora ou já resolvida antes.",
            content = @Content(schema = @Schema(implementation = ResolucaoQuarentenaDTO.class)))
    @APIResponse(responseCode = "404", description = "Id desconhecido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "409", description = "Falhou de novo (continua em quarentena) ou outra ação na mesma linha está em andamento.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "422", description = "Linha sem payload ou tópico sem reprocessador neste serviço.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "403", description = "Token sem escopo quarentena:reprocessar.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    public Response reprocessar(@PathParam("id") long id) {
        return Response.ok(actor.reprocessar(id, autor())).header("Cache-Control", "no-store").build();
    }

    @POST
    @Path("/{id}/descarte")
    @Operation(summary = "Descartar a mensagem por decisão operacional, sem aplicar efeito; repetição é inócua")
    @APIResponse(responseCode = "200", description = "Descartada agora ou já resolvida antes.",
            content = @Content(schema = @Schema(implementation = ResolucaoQuarentenaDTO.class)))
    @APIResponse(responseCode = "409", description = "Reprocessamento da mesma linha em andamento.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "404", description = "Id desconhecido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "401", description = "Token ausente ou inválido.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    @APIResponse(responseCode = "403", description = "Token sem escopo quarentena:reprocessar.",
            content = @Content(mediaType = Problemas.PROBLEM_JSON, schema = @Schema(implementation = ProblemaDTO.class)))
    public Response descartar(@PathParam("id") long id) {
        return Response.ok(actor.descartar(id, autor())).header("Cache-Control", "no-store").build();
    }
}
